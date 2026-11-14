package com.ntech.cabosse.dispatch.service;

import com.ntech.cabosse.shared.storage.AttachmentService;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.dispatch.dto.CreateDispatchNoteDto;
import com.ntech.cabosse.dispatch.dto.DispatchLineInputDto;
import com.ntech.cabosse.dispatch.dto.DispatchNoteResponseDto;
import com.ntech.cabosse.dispatch.entity.DispatchLine;
import com.ntech.cabosse.dispatch.entity.DispatchNoteEntity;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;
import com.ntech.cabosse.dispatch.repository.DispatchNoteRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bordereau de sortie (épic magasin, CE-195, modèle de l'expert du
 * 05/09/2026).
 *
 * <p>Le chargement appelle des reçus d'achat, au besoin partiellement :
 * chaque appel est un décrément conditionnel atomique sur le disponible
 * du reçu, deux chargements simultanés ne sortant jamais deux fois le
 * même reliquat. Chaque ligne sort du stock au CMUP photographié, avec le
 * lot du reçu : la traçabilité aval lit alors l'exact, plus l'inféré. La
 * vente appellera le bordereau (CE-194) et ne sortira plus de stock.</p>
 *
 * <p>Le brut d'une ligne se déduit du net et des sacs (brut = net + sacs,
 * la règle constante du carnet), le magasinier ne pesant pas une seconde
 * fois ce qu'il a pesé à la réception.</p>
 */
@ApplicationScoped
public class DispatchNoteService {

    @Inject DispatchNoteRepository repo;
    @Inject DispatchNoteRefService refService;
    @Inject ProducerPurchaseRepository receipts;
    @Inject CustomerRepository customers;
    @Inject SiteRepository sites;
    @Inject CampaignResolver campaignResolver;
    @Inject StockService stockService;
    @Inject com.ntech.cabosse.stock.repository.StockMovementRepository movements;
    @Inject StockItemRepository stockItems;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;
    @Inject AttachmentService attachments;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<DispatchNoteResponseDto> page(DispatchNoteStatus status, UUID siteId,
                                                    PageRequest pr) {
        return page(status, siteId, null, null, pr);
    }

    /** Bornée par dates : les chargements d'un jour précis (31/10/2026). */
    public Pagination<DispatchNoteResponseDto> page(DispatchNoteStatus status, UUID siteId,
                                                    java.time.LocalDate from, java.time.LocalDate to,
                                                    PageRequest pr) {
        long total = repo.count(status, siteId, from, to);
        var sort = DispatchNoteRepository.SORTABLE.resolve(pr);
        List<DispatchNoteResponseDto> items = repo
                .list(status, siteId, from, to, pr.skip(), pr.perPage(), sort)
                .stream().map(DispatchNoteResponseDto::from).toList();
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (from != null) filters.put("from", from.toString());
        if (to != null) filters.put("to", to.toString());
        return Pagination.of(total, pr, sort.names(), sort.order(), filters, items);
    }

    public DispatchNoteResponseDto getById(UUID id) {
        return DispatchNoteResponseDto.from(loadOrFail(id));
    }

    public DispatchNoteEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dsp-note-not-found", id)));
    }

    // ─── Création ───────────────────────────────────────────────────

    public DispatchNoteResponseDto create(CreateDispatchNoteDto p) {
        if (p.siteId() == null) {
            throw new BusinessException(Messages.msg("m.stk-site-required"));
        }
        List<CalledSource> called = resolveSources(p.siteId(), p.lines());
        takeAll(p.lines(), called);

        DispatchNoteEntity e = new DispatchNoteEntity();
        try {
            Instant now = Instant.now();
            e.id = idGenerator.newId();
            e.ref = refService.next();
            e.siteId = p.siteId();
            e.siteName = sites.findById(p.siteId()).map(s -> s.name).orElse(null);
            fill(e, p, called);
            e.createdAt = now;
            e.updatedAt = now;
            e.createdByEmail = actor();
            repo.insert(e);
            for (DispatchLine line : e.lines) applyOut(e, line, false);
        } catch (RuntimeException ex) {
            releaseAll(p.lines());
            throw ex;
        }

        audit.event(AuditEventType.STOCK_MOVEMENT_RECORDED)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Bordereau de sortie " + e.ref + " : " + e.totalNetKg + " kg "
                        + e.articleName + " en " + e.lines.size() + " appel(s)"
                        + (e.customerName != null ? " vers " + e.customerName : ""))
                .record();
        return DispatchNoteResponseDto.from(e);
    }

    // ─── Correction ─────────────────────────────────────────────────

    /**
     * Corrige un chargement tant qu'aucune vente ne l'a appelé (demandé
     * le 14/11/2026) : lignes, sacs, client, camion, date.
     *
     * <p>Annuler puis refaire consommait un nouveau numéro, alors que le
     * papier du camion porte déjà le premier. La correction garde la
     * référence et les pièces jointes : les anciennes lignes rendent
     * leurs kilos aux sources et contre-passent leurs sorties, les
     * nouvelles appellent et sortent comme à la création. Le bordereau
     * est verrouillé le temps de l'opération, et une vente ne peut pas
     * l'appeler pendant ce temps.</p>
     */
    public DispatchNoteResponseDto update(UUID id, CreateDispatchNoteDto p) {
        DispatchNoteEntity e = loadOrFail(id);
        if (e.status != DispatchNoteStatus.OPEN) {
            throw new BusinessException(Messages.msg("m.dsp-edit-not-open", e.ref));
        }
        if (p.siteId() != null && !p.siteId().equals(e.siteId)) {
            throw new BusinessException(Messages.msg("m.dsp-edit-other-site", e.ref));
        }
        if (!repo.tryLockForEdit(e.id)) {
            throw new BusinessException(Messages.msg("m.dsp-edit-busy", e.ref));
        }
        try {
            List<DispatchLine> previous = e.lines;
            List<DispatchLineInputDto> previousInputs = previous.stream()
                    .map(l -> new DispatchLineInputDto(l.receiptId, l.openingMovementId,
                            l.netKg, l.bagsCount, l.comment))
                    .toList();

            // Les anciennes lignes rendent leurs kilos : la nouvelle
            // composition peut reprendre ce que le chargement tenait.
            releaseAll(previousInputs);
            List<CalledSource> called;
            try {
                called = resolveSources(e.siteId, p.lines());
                takeAll(p.lines(), called);
            } catch (RuntimeException ex) {
                retakeAll(previousInputs);
                throw ex;
            }

            // Les sorties : contre-passer les anciennes, puis sortir les
            // nouvelles. Chaque mouvement passé est noté pour être défait
            // si une sortie échoue en route.
            List<Runnable> undo = new ArrayList<>();
            DispatchNoteEntity before = headerOf(e);
            try {
                for (DispatchLine line : List.copyOf(previous)) {
                    applyReversal(before, line, "Correction " + e.ref);
                    undo.add(() -> applyOutAt(before, line, before.date, true));
                }
                fill(e, p, called);
                for (DispatchLine line : e.lines) {
                    applyOut(e, line, false);
                    undo.add(() -> applyReversal(e, line, "Correction " + e.ref));
                }
            } catch (RuntimeException ex) {
                for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
                releaseAll(p.lines());
                retakeAll(previousInputs);
                throw ex;
            }

            e.editLockedAt = null;
            e.updatedAt = Instant.now();
            e.correctedAt = e.updatedAt;
            e.correctedByEmail = actor();
            repo.replace(e);
        } catch (RuntimeException ex) {
            repo.unlockEdit(e.id);
            throw ex;
        }

        audit.event(AuditEventType.STOCK_MOVEMENT_RECORDED)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Correction du bordereau de sortie " + e.ref + " : " + e.totalNetKg
                        + " kg en " + e.totalBags + " sacs, " + e.lines.size() + " appel(s)"
                        + (e.customerName != null ? " vers " + e.customerName : ""))
                .record();
        return DispatchNoteResponseDto.from(e);
    }

    // ─── Composition ────────────────────────────────────────────────

    /**
     * Les sources appelées, toutes du même article et du même site : un
     * camion charge une matière, pas un assortiment. Une source est un
     * reçu d'achat, ou le stock de départ quand la matière vient de là
     * (expert, 14/10/2026). Le vrac appelable du site, brassage déjà
     * réparti, dit ce qui reste sur chaque source, et une perte ne se
     * retranche qu'une fois même quand le magasin tient plusieurs vracs
     * (16/10/2026).
     */
    private List<CalledSource> resolveSources(UUID siteId, List<DispatchLineInputDto> lines) {
        java.util.Map<UUID, com.ntech.cabosse.stock.dto.DispatchableOpeningDto> bulk =
                new java.util.HashMap<>();
        for (var source : stockService.dispatchableOpenings(siteId, null)) {
            bulk.put(source.movementId(), source);
        }

        List<CalledSource> called = new ArrayList<>();
        for (DispatchLineInputDto line : lines) {
            if ((line.receiptId() == null) == (line.openingMovementId() == null)) {
                throw new BusinessException(Messages.msg("m.dsp-source-exclusive"));
            }
            if (line.receiptId() != null) {
                ProducerPurchaseEntity receipt = receipts.findById(line.receiptId()).orElseThrow(
                        () -> new NotFoundException(
                                Messages.msg("m.ppu-receipt-not-found", line.receiptId())));
                if (receipt.isCancelled()) {
                    throw new BusinessException(Messages.msg("m.dsp-receipt-cancelled", receipt.ref));
                }
                if (!siteId.equals(receipt.siteId)) {
                    throw new BusinessException(Messages.msg("m.dsp-receipt-other-site", receipt.ref));
                }
                called.add(CalledSource.of(receipt));
            } else {
                var opening = movements.findById(line.openingMovementId()).orElseThrow(
                        () -> new NotFoundException(
                                Messages.msg("m.dsp-opening-not-found", line.openingMovementId())));
                if (!siteId.equals(opening.siteId)) {
                    throw new BusinessException(
                            Messages.msg("m.dsp-receipt-other-site", opening.ref));
                }
                var available = bulk.get(opening.id);
                if (available == null) {
                    throw new BusinessException(
                            Messages.msg("m.dsp-source-exhausted", opening.ref));
                }
                called.add(CalledSource.of(opening, available));
            }
        }
        UUID articleId = called.get(0).articleId();
        for (CalledSource source : called) {
            if (!articleId.equals(source.articleId())) {
                throw new BusinessException(Messages.msg("m.dsp-mixed-articles",
                        called.get(0).articleName(), source.articleName()));
            }
        }
        return called;
    }

    /**
     * Appels atomiques sur le disponible de chaque source, avec retour
     * arrière complet si l'une d'elles ne peut pas suivre.
     */
    private void takeAll(List<DispatchLineInputDto> lines, List<CalledSource> called) {
        List<DispatchLineInputDto> consumed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            DispatchLineInputDto line = lines.get(i);
            boolean taken = line.receiptId() != null
                    ? receipts.tryDispatch(line.receiptId(), line.netKg())
                    : movements.tryDispatchOpening(line.openingMovementId(), line.netKg(),
                            called.get(i).reservedKg());
            if (!taken) {
                releaseAll(consumed);
                CalledSource source = called.get(i);
                throw new BusinessException(Messages.msg("m.dsp-receipt-exhausted",
                        source.ref(), line.netKg(), source.availableKg()));
            }
            consumed.add(line);
        }
    }

    /** Pose sur le bordereau ce que la saisie décrit : en-tête, lignes et totaux. */
    private void fill(DispatchNoteEntity e, CreateDispatchNoteDto p, List<CalledSource> called) {
        e.date = p.date();
        CalledSource first = called.get(0);
        e.articleId = first.articleId();
        e.articleCode = first.articleCode();
        e.articleName = first.articleName();
        e.articleUnit = first.articleUnit();
        e.customerId = null;
        e.customerName = null;
        if (p.customerId() != null) {
            customers.findById(p.customerId()).ifPresent(c -> {
                e.customerId = c.id;
                e.customerName = c.name;
            });
        }
        e.truckNumber = blankToNull(p.truckNumber());
        e.destination = blankToNull(p.destination());
        // La campagne vient de la date du bordereau, pas de l'heure de
        // la saisie : une expédition de juillet enregistrée en octobre
        // appartient à la campagne de juillet (04/10/2026).
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.date, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.notes = blankToNull(p.notes());

        BigDecimal cmup = stockItems.findByArticleAndSite(e.articleId, e.siteId)
                .map(it -> it.cmup).orElse(BigDecimal.ZERO);

        List<DispatchLine> lines = new ArrayList<>(p.lines().size());
        BigDecimal totalGross = BigDecimal.ZERO;
        int totalBags = 0;
        BigDecimal totalNet = BigDecimal.ZERO;
        for (int i = 0; i < p.lines().size(); i++) {
            DispatchLineInputDto input = p.lines().get(i);
            CalledSource source = called.get(i);
            DispatchLine line = new DispatchLine();
            line.receiptId = input.receiptId();
            line.openingMovementId = input.openingMovementId();
            line.receiptRef = source.ref();
            line.lotRef = source.lotRef();
            line.netKg = input.netKg();
            line.comment = input.comment() == null || input.comment().isBlank()
                    ? null : input.comment().trim();
            line.bagsCount = input.bagsCount();
            line.grossKg = input.bagsCount() != null
                    ? input.netKg().add(BigDecimal.valueOf(input.bagsCount()))
                    : input.netKg();
            line.cmupAtDispatch = cmup;
            lines.add(line);
            totalGross = totalGross.add(line.grossKg);
            totalBags += input.bagsCount() != null ? input.bagsCount() : 0;
            totalNet = totalNet.add(input.netKg());
        }
        e.lines = lines;
        e.totalGrossKg = totalGross;
        e.totalBags = totalBags;
        e.totalNetKg = totalNet;
    }

    /**
     * Une sortie de stock par ligne, sous le lot de son reçu : la
     * traçabilité aval y lira l'exact. Les sacs partent avec elle : le
     * registre du magasin compte en sacs autant qu'en kilos (expert,
     * 16/10/2026).
     */
    private void applyOut(DispatchNoteEntity e, DispatchLine line, boolean force) {
        applyOutAt(e, line, e.date, force);
    }

    private void applyOutAt(DispatchNoteEntity e, DispatchLine line, LocalDate date, boolean force) {
        stockService.applyMovement(new MovementInput(
                e.articleId, e.siteId, MovementKind.OUT, line.netKg, line.cmupAtDispatch,
                MovementSource.DISPATCH_NOTE, e.ref, e.id, null,
                "Chargement " + e.ref + (e.customerName != null ? " vers " + e.customerName : ""),
                null,
                date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                force, line.lotRef, false, line.bagsCount));
    }

    /** L'en-tête d'avant une correction, de quoi défaire ses mouvements. */
    private static DispatchNoteEntity headerOf(DispatchNoteEntity e) {
        DispatchNoteEntity h = new DispatchNoteEntity();
        h.id = e.id;
        h.ref = e.ref;
        h.siteId = e.siteId;
        h.articleId = e.articleId;
        h.customerName = e.customerName;
        h.date = e.date;
        return h;
    }

    /** Rend au stock ce qu'une ligne en avait sorti. */
    private void applyReversal(DispatchNoteEntity e, DispatchLine line, String label) {
        stockService.applyMovement(new MovementInput(
                e.articleId, e.siteId, MovementKind.IN, line.netKg, line.cmupAtDispatch,
                MovementSource.DISPATCH_NOTE, e.ref, e.id, null,
                label, null,
                Instant.now(), true, line.lotRef, false, line.bagsCount));
    }

    // ─── Annulation ─────────────────────────────────────────────────

    public DispatchNoteResponseDto cancel(UUID id, String reason) {
        DispatchNoteEntity e = loadOrFail(id);
        if (e.status == DispatchNoteStatus.CANCELLED) {
            throw new BusinessException(Messages.msg("m.dsp-already-cancelled", e.ref));
        }
        if (e.status == DispatchNoteStatus.SOLD) {
            throw new BusinessException(Messages.msg("m.dsp-cancel-sold", e.ref, e.saleRef));
        }

        // Le stock revient ligne à ligne, chaque source retrouve son
        // reliquat : un reçu d'achat, ou le stock de départ.
        for (DispatchLine line : e.lines) {
            applyReversal(e, line, "Contre-passation " + e.ref);
            if (line.receiptId != null) {
                receipts.releaseDispatch(line.receiptId, line.netKg);
            } else if (line.openingMovementId != null) {
                movements.releaseDispatchOpening(line.openingMovementId, line.netKg);
            }
        }

        e.status = DispatchNoteStatus.CANCELLED;
        e.cancellationReason = reason == null ? "" : reason.trim();
        e.cancelledAt = Instant.now();
        e.cancelledByEmail = actor();
        e.updatedAt = e.cancelledAt;
        repo.replace(e);

        audit.event(AuditEventType.STOCK_MOVEMENT_RECORDED)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Contre-passation du bordereau de sortie " + e.ref + " : "
                        + e.cancellationReason)
                .record();
        return DispatchNoteResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Reprend sur leurs sources les kilos qu'une correction avait rendus,
     * quand la nouvelle composition échoue : le chargement retrouve
     * exactement ce qu'il tenait.
     */
    private void retakeAll(List<DispatchLineInputDto> lines) {
        for (DispatchLineInputDto line : lines) {
            if (line.receiptId() != null) {
                receipts.releaseDispatch(line.receiptId(), line.netKg().negate());
            } else {
                movements.releaseDispatchOpening(line.openingMovementId(), line.netKg().negate());
            }
        }
    }

    /** Rend à leurs sources les kilos déjà appelés. */
    private void releaseAll(List<DispatchLineInputDto> consumed) {
        for (DispatchLineInputDto done : consumed) {
            if (done.receiptId() != null) {
                receipts.releaseDispatch(done.receiptId(), done.netKg());
            } else {
                movements.releaseDispatchOpening(done.openingMovementId(), done.netKg());
            }
        }
    }

    /**
     * Ce qu'une ligne appelle, quelle que soit sa source : un reçu
     * d'achat, ou le stock de départ.
     */
    private record CalledSource(UUID articleId, String articleCode, String articleName,
                                String articleUnit, String ref, String lotRef,
                                BigDecimal availableKg, BigDecimal reservedKg) {

        static CalledSource of(ProducerPurchaseEntity r) {
            return new CalledSource(r.articleId, r.articleCode, r.articleName, r.articleUnit,
                    r.ref, r.deliveryRef != null ? r.deliveryRef : r.ref,
                    nz(r.weightKg).subtract(nz(r.dispatchedKg)), BigDecimal.ZERO);
        }

        static CalledSource of(com.ntech.cabosse.stock.entity.StockMovementEntity m,
                               com.ntech.cabosse.stock.dto.DispatchableOpeningDto available) {
            // Le lot d'un vrac est sa propre référence : ni l'amorçage
            // ni un transfert reçu n'ont d'autre origine à citer. Ce que
            // le brassage lui a pris est déjà retranché du disponible ;
            // on le redit au plafond atomique pour qu'il tienne aussi
            // face à un chargement concurrent.
            BigDecimal reserved = nz(m.quantitySigned).abs()
                    .subtract(nz(m.dispatchedKg))
                    .subtract(nz(available.availableKg()))
                    .max(BigDecimal.ZERO);
            return new CalledSource(m.articleId, m.articleCode, m.articleName, m.articleUnit,
                    m.ref, m.lotRef != null ? m.lotRef : m.ref,
                    nz(available.availableKg()), reserved);
        }
    }
    // ─── Pièces jointes ─────────────────────────────────────────────

    /** Dépose une pièce justificative sur dispatch note (demandé le 22/10/2026). */
    public DispatchNoteResponseDto attach(UUID id, byte[] bytes, String mimeType, String fileName, String label) {
        DispatchNoteEntity e = loadOrFail(id);
        var ref = attachments.store(bytes, mimeType, fileName, label, e.id, "dispatch_note");
        repo.pushAttachment(e.id, ref);
        audit.event(AuditEventType.DISPATCH_NOTE_ATTACHMENT)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » ajoutée sur " + e.ref)
                .record();
        return DispatchNoteResponseDto.from(loadOrFail(id));
    }

    /** Retire une pièce et archive son binaire. */
    public DispatchNoteResponseDto detach(UUID id, UUID fileId) {
        DispatchNoteEntity e = loadOrFail(id);
        var ref = attachments.find(e.attachments, fileId);
        repo.pullAttachment(e.id, fileId);
        attachments.discard(ref);
        audit.event(AuditEventType.DISPATCH_NOTE_ATTACHMENT)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » retirée de " + e.ref)
                .record();
        return DispatchNoteResponseDto.from(loadOrFail(id));
    }

    /** Contenu d'une pièce, servi en téléchargement. */
    public com.ntech.cabosse.shared.storage.AttachmentService.AttachmentStream openAttachment(
            UUID id, UUID fileId) {
        return attachments.open(loadOrFail(id).attachments, fileId);
    }
}
