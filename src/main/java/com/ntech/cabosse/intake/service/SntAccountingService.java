package com.ntech.cabosse.intake.service;

import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.intake.dto.SntAccountingRequestDto;
import com.ntech.cabosse.intake.dto.SntCommitResultDto;
import com.ntech.cabosse.intake.dto.SntLineDto;
import com.ntech.cabosse.intake.dto.SntPreviewDto;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.service.MemberService;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.producerpurchase.service.DeliveryNoteRefService;
import com.ntech.cabosse.producerpurchase.service.ProducerPurchaseService;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ntech.cabosse.intake.service.IntakeNoteService.clean;
import static com.ntech.cabosse.intake.service.IntakeNoteService.normalize;
import static com.ntech.cabosse.intake.service.IntakeNoteService.parseDate;
import static com.ntech.cabosse.intake.service.IntakeNoteService.parseDecimal;
import static com.ntech.cabosse.intake.service.IntakeNoteService.phoneKey;

/**
 * Comptabilisation d'un bordereau de réception (épic CE-218) : le
 * comptable importe l'extrait de traçabilité nationale (détail par
 * producteur) et la validation crée les reçus d'achat, seuls à écrire le
 * stock, la dette et l'apurement d'avance (schéma condensé, arbitré le
 * 09/09/2026).
 *
 * <p>Le producteur se reconnaît par ses 8 derniers chiffres de
 * téléphone, à défaut par son nom normalisé ; absent du registre, il est
 * créé au passage, comme sur les autres imports. Le montant du fichier
 * fait foi (pièce officielle) ; l'écart avec le barème de campagne est
 * signalé sans bloquer.</p>
 */
@ApplicationScoped
public class SntAccountingService {

    private static final Logger log = Logger.getLogger(SntAccountingService.class);

    @Inject IntakeNoteService intakeNotes;
    @Inject IntakeNoteRepository intakeRepo;
    @Inject MemberRepository members;
    @Inject MemberService memberService;
    @Inject ArticleRepository articles;
    @Inject CampaignRepository campaigns;
    @Inject com.ntech.cabosse.supplier.repository.SupplierRepository suppliers;
    @Inject ProducerPurchaseService purchaseService;
    @Inject DeliveryNoteRefService deliveryNoteRefService;
    @Inject JsonWebToken jwt;

    public SntPreviewDto preview(UUID intakeId, SntAccountingRequestDto request) {
        IntakeNoteEntity note = intakeNotes.loadOrFail(intakeId);
        return analyse(note, request);
    }

    public SntCommitResultDto commit(UUID intakeId, SntAccountingRequestDto request) {
        IntakeNoteEntity note = intakeNotes.loadOrFail(intakeId);
        if (!IntakeNoteEntity.STATUS_TO_ACCOUNT.equals(note.status)) {
            throw new BusinessException(Messages.msg("m.itk-already-accounted", note.ref));
        }
        SntPreviewDto preview = analyse(note, request);
        if (preview.delegateUnmatched()) {
            throw new BusinessException(Messages.msg(
                    "m.itk-delegate-unmatched", clean(note.supplierName)));
        }
        if (preview.rows().stream().allMatch(r -> "INVALID".equals(r.status()))) {
            throw new BusinessException(Messages.msg("m.itk-nothing-to-account"));
        }

        // La réservation précède la création : deux validations
        // concurrentes ne doivent pas doubler les reçus.
        if (!intakeRepo.claimAccounting(note.id, actor())) {
            throw new BusinessException(Messages.msg("m.itk-already-accounted", note.ref));
        }

        int createdMembers = 0;
        List<String> refs = new ArrayList<>();
        List<SntPreviewDto.Row> skipped = new ArrayList<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<String, String> deliveryRefs = new HashMap<>();
        for (SntPreviewDto.Row row : preview.rows()) {
            if ("INVALID".equals(row.status())) {
                skipped.add(row);
                continue;
            }
            try {
                UUID memberId = row.memberId();
                if (memberId == null) {
                    memberId = createMember(row, note);
                    createdMembers++;
                }
                UUID saleDelegate = preview.delegateSupplierId();
                String deliveryRef = saleDelegate == null ? null
                        : deliveryRefs.computeIfAbsent(
                                saleDelegate + "|" + row.date(),
                                k -> deliveryNoteRefService.next());
                var created = purchaseService.create(new ProducerPurchaseUpsertDto(
                        row.date(),
                        row.reference(),
                        null,
                        memberId,
                        request.articleId(),
                        // Le site vient du bordereau : c'est le magasin qui
                        // sait où la matière est entrée, pas la comptabilité
                        // (11/09/2026). Le paramètre ne sert plus que de
                        // repli pour les bordereaux importés sans site.
                        note.siteId != null ? note.siteId : request.siteId(),
                        note.campaignId,
                        note.truckNumber,
                        null,
                        null,
                        row.weightKg(),
                        row.pricePerKg(),
                        row.amount(),
                        // Le fichier officiel dit « paiement effectué » :
                        // le producteur est réputé réglé en entier.
                        row.amount(),
                        PaymentMethod.valueOf(row.paymentMethod()),
                        null,
                        null,
                        null,
                        null,
                        saleDelegate,
                        deliveryRef,
                        null));
                refs.add(created.ref());
                totalWeight = totalWeight.add(nz(row.weightKg()));
                totalAmount = totalAmount.add(nz(row.amount()));
            } catch (RuntimeException e) {
                log.warnf("SNT row %d skipped on %s: %s",
                        row.rowNumber(), note.ref, e.getMessage());
                skipped.add(new SntPreviewDto.Row(row.rowNumber(), "INVALID",
                        List.of(e.getMessage()), row.reference(), row.date(),
                        row.producerName(), row.producerPhone(), row.memberId(),
                        row.memberName(), row.memberToCreate(), row.weightKg(),
                        row.amount(), row.pricePerKg(), row.paymentMethod()));
            }
        }

        // Aucune ligne passée : « comptabilisé » serait un mensonge et le
        // comptable ne verrait qu'un état inchangé (constaté le
        // 11/09/2026 : le même extrait SNT rejoué sur plusieurs
        // bordereaux, chaque reçu officiel refusé en doublon). Le
        // bordereau est rendu à comptabiliser et la première raison
        // remonte en clair.
        if (refs.isEmpty()) {
            intakeRepo.reopenAccounting(note.id);
            String reason = skipped.isEmpty() || skipped.get(0).issues().isEmpty()
                    ? "" : skipped.get(0).issues().get(0);
            throw new BusinessException(Messages.msg("m.itk-commit-all-skipped", reason));
        }

        intakeRepo.finishAccounting(note.id, totalWeight, totalAmount, refs);
        BigDecimal gap = note.netWeightKg != null
                ? note.netWeightKg.subtract(totalWeight) : null;
        return new SntCommitResultDto(refs.size(), createdMembers, skipped.size(),
                totalWeight, totalAmount, gap, refs, skipped);
    }

    // ─── Analyse commune à la prévisualisation et à la validation ───

    private SntPreviewDto analyse(IntakeNoteEntity note, SntAccountingRequestDto request) {
        ArticleEntity article = articles.findById(request.articleId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.itk-article-not-found")));
        if (article.id == null) {
            throw new NotFoundException(Messages.msg("m.itk-article-not-found"));
        }
        BigDecimal basePrice = note.campaignId != null
                ? campaigns.findById(note.campaignId)
                        .map((CampaignEntity c) -> c.basePricePerKg).orElse(null)
                : null;

        // Le délégué : celui du bordereau s'il a été reconnu à l'import,
        // sinon celui que le fichier de traçabilité nomme (téléphone
        // d'abord, nom ensuite). Nommé mais introuvable, la validation
        // refusera : un reçu sans rattachement n'apure jamais un compte
        // d'avances (constaté en production le 10/09/2026).
        java.util.List<com.ntech.cabosse.supplier.entity.SupplierEntity> collectors =
                suppliers.listAll().stream().filter(su -> su.collector).toList();
        UUID delegateId = note.delegateSupplierId;
        String namedDelegate = clean(note.supplierName);
        if (delegateId == null && request.lines() != null) {
            for (SntLineDto raw : request.lines()) {
                String phone = phoneKey(raw.delegatePhone());
                String name = clean(raw.delegateName());
                if (namedDelegate == null && name != null) namedDelegate = name;
                if (phone != null) {
                    var hit = collectors.stream()
                            .filter(su -> phone.equals(phoneKey(su.phone)))
                            .toList();
                    if (hit.size() == 1) { delegateId = hit.get(0).id; break; }
                }
                if (name != null) {
                    String wanted = normalize(name);
                    var hit = collectors.stream()
                            .filter(su -> su.name != null && normalize(su.name).equals(wanted))
                            .toList();
                    if (hit.size() == 1) { delegateId = hit.get(0).id; break; }
                }
            }
        }
        boolean delegateUnmatched = delegateId == null && namedDelegate != null;
        final UUID resolvedDelegateId = delegateId;
        String delegateName = resolvedDelegateId == null ? null
                : collectors.stream().filter(su -> su.id.equals(resolvedDelegateId))
                        .map(su -> su.name).findFirst().orElse(null);

        // Index des membres : téléphone d'abord (8 derniers chiffres),
        // nom normalisé ensuite. Un téléphone partagé par deux fiches ne
        // tranche pas : on retombe sur le nom.
        List<MemberEntity> all = members.listAll();
        Map<String, List<MemberEntity>> byPhone = new HashMap<>();
        Map<String, List<MemberEntity>> byName = new HashMap<>();
        for (MemberEntity m : all) {
            String pk = phoneKey(m.phone);
            if (pk != null) byPhone.computeIfAbsent(pk, k -> new ArrayList<>()).add(m);
            byName.computeIfAbsent(normalize(m.name), k -> new ArrayList<>()).add(m);
        }

        List<SntPreviewDto.Row> rows = new ArrayList<>();
        int ready = 0, warning = 0, invalid = 0, toCreate = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (SntLineDto raw : request.lines() == null ? List.<SntLineDto>of() : request.lines()) {
            List<String> issues = new ArrayList<>();
            boolean blocking = false;

            String producerName = clean(raw.producerName());
            if (producerName == null) {
                issues.add(Messages.msg("m.itk-producer-name-required"));
                blocking = true;
            }
            LocalDate date = parseDate(raw.date());
            if (date == null) {
                date = note.date;
            }
            BigDecimal weight = parseDecimal(raw.weightKg());
            if (weight == null || weight.signum() <= 0) {
                issues.add(Messages.msg("m.itk-weight-required"));
                blocking = true;
            }
            BigDecimal amount = parseDecimal(raw.amount());
            if (amount == null || amount.signum() <= 0) {
                issues.add(Messages.msg("m.itk-amount-required"));
                blocking = true;
            }

            MemberEntity matched = null;
            boolean memberToCreate = false;
            if (producerName != null) {
                String pk = phoneKey(raw.producerPhone());
                List<MemberEntity> phoneHits = pk == null ? List.of()
                        : byPhone.getOrDefault(pk, List.of());
                if (phoneHits.size() == 1) {
                    matched = phoneHits.get(0);
                    if (!normalize(matched.name).equals(normalize(producerName))) {
                        issues.add(Messages.msg("m.itk-name-phone-mismatch", matched.name));
                    }
                } else {
                    List<MemberEntity> nameHits =
                            byName.getOrDefault(normalize(producerName), List.of());
                    if (nameHits.size() == 1) {
                        matched = nameHits.get(0);
                    } else if (nameHits.size() > 1) {
                        issues.add(Messages.msg("m.itk-producer-ambiguous"));
                        blocking = true;
                    } else {
                        memberToCreate = true;
                        issues.add(Messages.msg("m.itk-producer-will-be-created"));
                    }
                }
            }

            BigDecimal price = weight != null && weight.signum() > 0 && amount != null
                    ? amount.divide(weight, 2, RoundingMode.HALF_UP) : null;
            if (price != null && basePrice != null && basePrice.signum() > 0
                    && price.subtract(basePrice).abs().compareTo(new BigDecimal("0.5")) > 0) {
                issues.add(Messages.msg("m.itk-price-off-scale",
                        price.toPlainString(), basePrice.toPlainString()));
            }

            // Réglé sur carte : le mode du reçu suit l'argent réel.
            BigDecimal card = parseDecimal(raw.amountCard());
            String method = card != null && card.signum() > 0
                    ? PaymentMethod.PRODUCER_CARD.name() : PaymentMethod.CASH.name();

            String status = blocking ? "INVALID" : (issues.isEmpty() ? "READY" : "WARNING");
            if (blocking) invalid++;
            else if (issues.isEmpty()) ready++;
            else warning++;
            if (!blocking && memberToCreate) toCreate++;
            if (!blocking) {
                totalWeight = totalWeight.add(nz(weight));
                totalAmount = totalAmount.add(nz(amount));
            }
            rows.add(new SntPreviewDto.Row(
                    raw.rowNumber(), status, issues,
                    clean(raw.reference()), date,
                    producerName, clean(raw.producerPhone()),
                    matched != null ? matched.id : null,
                    matched != null ? matched.name : null,
                    memberToCreate, weight, amount, price, method));
        }

        BigDecimal gap = note.netWeightKg != null
                ? note.netWeightKg.subtract(totalWeight) : null;
        return new SntPreviewDto(rows.size(), ready, warning, invalid, toCreate,
                totalWeight, totalAmount, note.netWeightKg, gap,
                resolvedDelegateId, delegateName, delegateUnmatched, rows);
    }

    /** Ouvre la fiche du producteur inconnu, rattachée à la section du bordereau. */
    private UUID createMember(SntPreviewDto.Row row, IntakeNoteEntity note) {
        UUID sectionId = null;
        var created = memberService.createImported(new MemberUpsertDto(
                null, null, row.producerName(), null,
                null, null, null, null,
                null, null,
                null, null,
                null, null, null, List.of(),
                null, null,
                sectionId, null, null, null,
                List.of(), List.of(),
                null, null, row.producerPhone(), null,
                null, null,
                null,
                MemberStatus.ACTIVE,
                null, null,
                null, Boolean.FALSE,
                "Créé à la comptabilisation du bordereau " + note.ref));
        return created.id();
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
