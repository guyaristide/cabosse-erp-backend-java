package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.dto.MemberFileStatusDto;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.service.MemberFileCompleteness;
import com.ntech.cabosse.members.service.MemberPaymentVigilance;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reçu d'achat de matière première au producteur membre (backlog NEG-01).
 *
 * <p>Résout le produit coop vers son article matière première (lien manuel),
 * applique les préférences tenant (prix garanti, montant, poids), poste
 * l'entrée stock au CMUP et l'écriture (601 / trésorerie en direct, 601 /
 * 4091 si rattaché à une avance délégué avec décrément de l'avance).</p>
 */
@ApplicationScoped
public class ProducerPurchaseService {

    @Inject ProducerPurchaseRepository repo;
    @Inject ProducerPurchaseRefService refService;
    @Inject MemberRepository members;
    @Inject SectionRepository sections;
    @Inject ArticleRepository articles;
    @Inject CampaignService campaigns;
    @Inject CollectorAdvanceRepository advances;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<ProducerPurchaseResponseDto> page(String q, Integer campaignYear,
                                                        UUID memberId, PageRequest pr) {
        long total = repo.countSearch(q, campaignYear, memberId);
        List<ProducerPurchaseResponseDto> items = repo
                .search(q, campaignYear, memberId, pr.skip(), pr.perPage())
                .stream().map(ProducerPurchaseResponseDto::from).toList();
        return Pagination.of(total, pr, new String[]{"date"}, "desc",
                new java.util.HashMap<>(), items);
    }

    public ProducerPurchaseResponseDto getById(UUID id) {
        return ProducerPurchaseResponseDto.from(loadOrFail(id));
    }

    // ─── Création (reçu) ────────────────────────────────────────────

    public ProducerPurchaseResponseDto create(ProducerPurchaseUpsertDto p) {
        TenantPreferences prefs = preferences.current();

        MemberEntity m = members.findById(p.memberId()).orElseThrow(
                () -> new NotFoundException("Producteur " + p.memberId() + " introuvable."));
        ensureProducerFileUsable(prefs, m);
        if (prefs.requireProducerPaymentVigilance()) {
            MemberPaymentVigilance.check(m, p.paymentMethod() != null ? p.paymentMethod().name() : null);
        }

        ArticleEntity article = articles.findById(p.articleId()).orElseThrow(
                () -> new NotFoundException("Article " + p.articleId() + " introuvable."));
        if (!article.purchasable) {
            throw new BusinessException("L'article « " + article.name + " » n'est pas marqué achetable.");
        }

        CampaignEntity campaign = p.campaignId() != null ? campaigns.get(p.campaignId()) : campaigns.current();

        BigDecimal weight = resolveWeight(prefs, p);
        BigDecimal price = resolvePrice(prefs, p, campaign);
        BigDecimal amount = resolveAmount(prefs, p, weight, price);

        UUID siteId = p.siteId();
        if (siteId == null) {
            throw new BusinessException("Site d'entrée en stock requis.");
        }

        // Avance rattachée : validation + payeur.
        CollectorAdvanceEntity advance = null;
        if (p.collectorAdvanceId() != null) {
            advance = advances.findById(p.collectorAdvanceId()).orElseThrow(
                    () -> new NotFoundException("Avance " + p.collectorAdvanceId() + " introuvable."));
            if (advance.status != CollectorAdvanceStatus.OPEN) {
                throw new BusinessException("Cette avance est clôturée — plus aucun achat imputable.");
            }
            if (amount.compareTo(advance.remainingFcfa) > 0) {
                throw new BusinessException("L'achat (" + amount + ") dépasse le solde de l'avance ("
                        + advance.remainingFcfa + ").");
            }
        }

        Instant now = Instant.now();
        ProducerPurchaseEntity e = new ProducerPurchaseEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.date = p.date();
        e.memberId = m.id;
        e.producerName = m.name;
        e.producerCode = m.code;
        e.producerExternalCode = firstExternalCode(m);
        e.village = m.village;
        e.producerPhone = m.phone;
        e.sectionId = m.sectionId;
        e.sectionName = m.sectionId != null
                ? sections.findById(m.sectionId).map(s -> s.name).orElse(null) : null;
        e.articleId = article.id;
        e.articleCode = article.code;
        e.articleName = article.name;
        e.articleUnit = article.unit;
        e.siteId = siteId;
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.nbSacs = p.nbSacs();
        e.weightKg = weight;
        e.guaranteedPricePerKgFcfa = price;
        e.amountFcfa = amount;
        e.paymentMethod = p.paymentMethod();
        e.paymentRef = blankToNull(p.paymentRef());
        e.collectorAdvanceId = p.collectorAdvanceId();
        if (advance != null) {
            e.payerName = advance.delegateName;
        } else if (p.payerMemberId() != null) {
            e.payerMemberId = p.payerMemberId();
            e.payerName = members.findById(p.payerMemberId()).map(x -> x.name).orElse(blankToNull(p.payerName()));
        } else {
            e.payerName = blankToNull(p.payerName());
        }
        e.createdAt = now;
        e.updatedAt = now;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // 1) Imputation ATOMIQUE de l'avance (décrément conditionnel en une
        //    opération) : réserve les fonds avant tout autre effet de bord.
        if (advance != null && !advances.tryImpute(advance.id, amount)) {
            throw new BusinessException("Avance close ou solde insuffisant pour cet achat ("
                    + amount + ").");
        }

        // 2) Écriture EN PREMIER : échoue tôt (période close, pièce déséquilibrée)
        //    avant tout mouvement de stock. En cas d'échec, on recrédite l'avance.
        String creditAccount;
        String creditLabel;
        if (advance != null) {
            creditAccount = prefs.collectorAdvanceAccount();
            creditLabel = "Apurement avance " + advance.ref;
        } else {
            creditAccount = accounting.treasuryAccountFor(p.paymentMethod());
            creditLabel = "Règlement achat " + e.ref;
        }
        try {
            accounting.postFromProducerPurchase(e.id, e.ref, article.id, parseType(article.type),
                            article.name, amount, p.date(), creditAccount, creditLabel)
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        } catch (RuntimeException ex) {
            if (advance != null) advances.creditBack(advance.id, amount);
            throw ex;
        }

        // 3) Entrée de stock au CMUP pondéré (coût = montant ÷ poids), datée du reçu.
        BigDecimal unitCost = amount.divide(weight, 6, RoundingMode.HALF_UP);
        stockService.applyMovement(new MovementInput(
                article.id, siteId, MovementKind.IN, weight, unitCost,
                MovementSource.PRODUCER_PURCHASE, e.ref, e.id, null,
                "Achat producteur " + e.producerName, null,
                p.date().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        e.movementRef = e.ref;

        repo.insert(e);

        audit.event(AuditEventType.PRODUCER_PURCHASE_CREATED)
                .actorEmail(actor())
                .target("producer_purchase", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Achat producteur " + e.ref + " : " + e.producerName
                        + " · " + weight + " kg " + e.articleName + " (" + amount + ")")
                .record();

        return ProducerPurchaseResponseDto.from(e);
    }

    // ─── Résolutions paramétrables ──────────────────────────────────

    private BigDecimal resolveWeight(TenantPreferences prefs, ProducerPurchaseUpsertDto p) {
        BigDecimal weight;
        if (TenantPreferences.PRODUCER_WEIGHT_FROM_BAGS.equals(prefs.producerWeightMode())) {
            if (p.nbSacs() == null || prefs.producerStandardBagKg == null) {
                throw new BusinessException("Mode « poids dérivé des sacs » : nombre de sacs et poids standard requis.");
            }
            weight = prefs.producerStandardBagKg.multiply(BigDecimal.valueOf(p.nbSacs()));
        } else {
            weight = p.weightKg();
        }
        if (weight == null || weight.signum() <= 0) {
            throw new BusinessException("Poids (kg) requis et strictement positif.");
        }
        return weight;
    }

    private BigDecimal resolvePrice(TenantPreferences prefs, ProducerPurchaseUpsertDto p, CampaignEntity campaign) {
        BigDecimal price;
        if (TenantPreferences.PRODUCER_PRICE_MANUAL.equals(prefs.producerPriceSource())) {
            price = p.guaranteedPricePerKgFcfa();
        } else {
            price = p.guaranteedPricePerKgFcfa() != null
                    ? p.guaranteedPricePerKgFcfa()
                    : (campaign != null ? campaign.basePricePerKgFcfa : null);
        }
        if (price == null || price.signum() < 0) {
            throw new BusinessException("Prix garanti (FCFA/kg) requis.");
        }
        return price;
    }

    private BigDecimal resolveAmount(TenantPreferences prefs, ProducerPurchaseUpsertDto p,
                                     BigDecimal weight, BigDecimal price) {
        BigDecimal amount;
        if (TenantPreferences.PRODUCER_AMOUNT_MANUAL.equals(prefs.producerAmountMode())) {
            amount = p.amountFcfa();
        } else {
            amount = weight.multiply(price);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Montant total payé requis et strictement positif.");
        }
        return amount;
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Garde optionnelle sur le dossier producteur (backlog MEM-11).
     * Désactivée par défaut : une structure qui démarre sa collecte n'a pas
     * encore de dossiers complets. Activée, elle empêche de payer un
     * producteur dont l'identité n'est pas établie ou dont l'enquête est
     * périmée, et dit précisément ce qui manque.
     */
    private void ensureProducerFileUsable(TenantPreferences prefs, MemberEntity m) {
        if (!prefs.blockProducerPurchaseOnIncompleteFile()) return;
        int validityMonths = prefs.producerFileValidityMonths();
        MemberFileStatusDto status = MemberFileCompleteness.evaluate(m, validityMonths);
        if (status.expired()) {
            throw new BusinessException("Dossier du producteur « " + m.name + " » périmé depuis le "
                    + status.expiresAt() + " : mettre à jour l'enquête avant d'enregistrer un achat.");
        }
        if (!status.missingFields().isEmpty()) {
            throw new BusinessException("Dossier du producteur « " + m.name + " » incomplet ("
                    + String.join(", ", status.missingFields()) + ").");
        }
    }

    private static String firstExternalCode(MemberEntity m) {
        if (m.externalProducerCodes == null) return null;
        return m.externalProducerCodes.stream()
                .filter(c -> c.number != null && !c.number.isBlank())
                .map(c -> c.number.trim())
                .findFirst().orElse(null);
    }

    private static ArticleType parseType(String name) {
        if (name == null) return ArticleType.RAW_MATERIAL;
        try { return ArticleType.valueOf(name); }
        catch (IllegalArgumentException e) { return ArticleType.RAW_MATERIAL; }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ProducerPurchaseEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException("Reçu d'achat " + id + " introuvable."));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
