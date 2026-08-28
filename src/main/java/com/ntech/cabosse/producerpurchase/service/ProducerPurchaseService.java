package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.dto.MemberFileStatusDto;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.service.MemberFileCompleteness;
import com.ntech.cabosse.members.service.MemberPaymentVigilance;
import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import com.ntech.cabosse.membercredit.service.MemberCreditService;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseCancellation;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseStatus;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.ErrorCode;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.members.service.MemberFileField;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
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
    @Inject CampaignResolver campaignResolver;
    @Inject CollectorAdvanceRepository advances;
    @Inject SupplierRepository suppliers;
    @Inject com.ntech.cabosse.members.service.ProducerRefKeyService producerRefKeys;
    @Inject MemberCreditService memberCredits;
    @Inject com.ntech.cabosse.suppliercategory.service.SupplierMarginResolver marginResolver;
    @Inject com.ntech.cabosse.suppliercategory.repository.SupplierCategoryRepository supplierCategories;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository producerPayments;
    @Inject com.ntech.cabosse.stock.repository.StockItemRepository stockItems;
    @Inject com.ntech.cabosse.stock.repository.StockMovementRepository stockMovements;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<ProducerPurchaseResponseDto> page(String q, UUID campaignId,
                                                        UUID memberId, PageRequest pr) {
        long total = repo.countSearch(q, campaignId, memberId);
        List<ProducerPurchaseResponseDto> items = repo
                .search(q, campaignId, memberId, pr.skip(), pr.perPage())
                .stream().map(ProducerPurchaseResponseDto::from).toList();
        return Pagination.of(total, pr, new String[]{"date"}, "desc",
                new java.util.HashMap<>(), items);
    }


    /** Toutes les lignes du filtre courant, pour l'export. */
    public List<ProducerPurchaseResponseDto> listForExport(String q, UUID campaignId, UUID memberId) {
        return repo.search(q, campaignId, memberId, 0, Integer.MAX_VALUE)
                .stream().map(ProducerPurchaseResponseDto::from).toList();
    }

    public ProducerPurchaseResponseDto getById(UUID id) {
        return ProducerPurchaseResponseDto.from(loadOrFail(id));
    }

    // ─── Création (reçu) ────────────────────────────────────────────

    public ProducerPurchaseResponseDto create(ProducerPurchaseUpsertDto p) {
        TenantPreferences prefs = preferences.current();

        MemberEntity m = members.findById(p.memberId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ppu-producer-not-found", p.memberId())));
        ensureProducerFileUsable(prefs, m, p.date());
        if (prefs.requireProducerPaymentVigilance()) {
            MemberPaymentVigilance.check(m, p.paymentMethod() != null ? p.paymentMethod().name() : null,
                    producerRefKeys.identityProofTypeNames());
        }

        ArticleEntity article = articles.findById(p.articleId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ach-article-not-found", p.articleId())));
        if (!article.purchasable) {
            throw new BusinessException(Messages.msg("m.ppu-article-not-purchasable", article.name));
        }

        // Un reçu officiel ne couvre qu'une opération : un numéro réutilisé
        // est le déguisement classique d'un détournement. Contrôlé ici,
        // avant tout effet de bord ; l'index unique posé par M062 ferme la
        // course résiduelle entre deux saisies simultanées.
        String officialReceipt = blankToNull(p.officialReceiptRef());
        if (officialReceipt != null) {
            repo.findByOfficialReceipt(officialReceipt).ifPresent(existing -> {
                throw new ConflictException(ErrorCode.DUPLICATE_RECEIPT,
                        Messages.msg("m.ppu-receipt-duplicate", officialReceipt, existing.ref));
            });
        }

        // Rattachement par la date du reçu, pas par le jour de la saisie :
        // un reçu de campagne principale ressaisi en avril restait sinon
        // compté dans la campagne intermédiaire.
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(p.date(), p.campaignId());

        BigDecimal weight = resolveWeight(prefs, p);
        BigDecimal price = resolvePrice(prefs, p, campaign);
        BigDecimal amount = resolveAmount(prefs, p, weight, price);

        UUID siteId = p.siteId();
        if (siteId == null) {
            throw new BusinessException(Messages.msg("m.ppu-stock-site-required"));
        }

        // Délégué rattaché : son compte courant porte le reçu. Aucun
        // plafond ici — il livre souvent plus que ce qu'il a reçu, et la
        // coopérative lui doit alors la différence jusqu'au décompte de
        // fin de campagne.
        SupplierEntity delegate = null;
        if (p.delegateSupplierId() != null) {
            delegate = suppliers.findById(p.delegateSupplierId()).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.ppu-delegate-not-found", p.delegateSupplierId())));
            if (!delegate.collector) {
                throw new BusinessException(Messages.msg("m.ppu-not-collector", delegate.name));
            }
        }
        // L'apporteur est le délégué quand il y en a un, sinon le
        // producteur par sa fiche fournisseur miroir. C'est lui qui porte
        // la catégorie de reprise, et donc les conditions appliquées.
        SupplierEntity carrier = delegate != null ? delegate
                : (m.supplierId != null ? suppliers.findById(m.supplierId).orElse(null) : null);
        var categoryOfCarrier = carrier != null
                ? supplierCategories.findById(carrier.categoryId).orElse(null) : null;
        // La rémunération reste attachée au délégué : un producteur qui
        // livre en direct est payé au prix, sans intermédiaire à
        // rétribuer. La catégorie sert alors au classement seul.
        BigDecimal margin = delegate != null
                ? marginResolver.resolve(prefs, delegate, categoryOfCarrier).on(weight, amount)
                : BigDecimal.ZERO;
        // Mise en compte : ce que la coopérative retient au délégué, par
        // kilo livré. Symétrique de la marge, qu'elle lui verse. Le taux
        // est celui de sa fiche, figé ici pour que les états d'une
        // campagne close ne bougent plus.
        BigDecimal retention = delegate != null && delegate.collectorRetentionPerKgFcfa != null
                ? delegate.collectorRetentionPerKgFcfa.multiply(weight).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // Retenues décidées sur les crédits du producteur. Une retenue
        // n'est pas un impayé : la livraison est intégralement soldée, une
        // part en espèces, l'autre en remboursement de dette. Elle réduit
        // donc le versement quel que soit le paramétrage du paiement
        // partiel.
        List<ProducerPurchaseUpsertDto.CreditImputationDto> imputations =
                p.creditImputations() == null ? List.of()
                        : p.creditImputations().stream().filter(java.util.Objects::nonNull).toList();
        BigDecimal creditImputed = imputations.stream()
                .map(ProducerPurchaseUpsertDto.CreditImputationDto::amountFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (creditImputed.compareTo(amount) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_INSUFFICIENT,
                    Messages.msg("m.ppu-credit-exceeds-amount",
                            String.valueOf(creditImputed), String.valueOf(amount)));
        }
        BigDecimal payable = amount.subtract(creditImputed);
        BigDecimal paid = resolvePaid(prefs, p, payable);

        Instant now = Instant.now();
        ProducerPurchaseEntity e = new ProducerPurchaseEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.date = p.date();
        e.memberId = m.id;
        e.producerName = m.name;
        e.producerCode = m.code;
        e.producerExternalCode = externalCodeFor(m, p.producerExternalCode(), prefs);
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
        e.officialReceiptRef = officialReceipt;
        e.amountPaidFcfa = paid;
        e.creditImputedFcfa = creditImputed;
        e.paymentMethod = p.paymentMethod();
        e.paymentRef = blankToNull(p.paymentRef());
        e.deliveryRef = blankToNull(p.deliveryRef());
        if (categoryOfCarrier != null) {
            e.supplierCategoryId = categoryOfCarrier.id;
            e.supplierCategoryName = categoryOfCarrier.name;
        }
        if (delegate != null) {
            e.delegateSupplierId = delegate.id;
            e.delegateName = delegate.name;
            e.delegateMarginFcfa = margin;
            e.delegateRetentionFcfa = retention;
            e.payerName = delegate.name;
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

        // 0) Le reçu est posé AVANT tout effet de bord. C'est lui qui porte
        //    l'unicité du numéro officiel : en le posant en premier, une
        //    saisie concurrente perd la course ici, alors qu'aucune avance
        //    n'a été imputée, aucune écriture passée, aucun stock bougé.
        //    Auparavant l'insertion venait en dernier, et la perdante
        //    laissait derrière elle une pièce comptable et un mouvement de
        //    stock orphelins, que le code assumait sans pouvoir les
        //    reprendre.
        try {
            repo.insert(e);
        } catch (com.mongodb.MongoWriteException dup) {
            if (com.mongodb.ErrorCategory.fromErrorCode(dup.getError().getCode())
                    != com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                throw dup;
            }
            throw new ConflictException(ErrorCode.DUPLICATE_RECEIPT,
                    Messages.msg("m.ppu-receipt-duplicate-race", officialReceipt));
        }

        // 1) Imputation du compte courant du délégué. Le reçu et sa
        //    rémunération réduisent tous deux ce qu'il doit. L'avance la
        //    plus ancienne encore ouverte porte l'écriture ; son solde
        //    peut devenir négatif, c'est le sens même du compte courant.
        BigDecimal imputed = amount.add(margin);
        CollectorAdvanceEntity advance = delegate != null
                ? advances.oldestOpenForDelegate(delegate.id).orElse(null) : null;
        if (advance != null) {
            advances.impute(advance.id, imputed);
            e.collectorAdvanceId = advance.id;
        }

        // 1 bis) Retenues sur les crédits : décrément atomique par
        //    engagement, avant l'écriture, pour qu'un solde insuffisant
        //    arrête l'opération avant tout effet de bord.
        List<MemberCreditEntity> imputedCredits = new java.util.ArrayList<>();
        try {
            for (ProducerPurchaseUpsertDto.CreditImputationDto imputation : imputations) {
                imputedCredits.add(memberCredits.impute(
                        imputation.creditId(), imputation.amountFcfa(), m.id));
            }
        } catch (RuntimeException ex) {
            for (int i = 0; i < imputedCredits.size(); i++) {
                memberCredits.creditBack(imputedCredits.get(i).id, imputations.get(i).amountFcfa());
            }
            if (advance != null) advances.creditBack(advance.id, imputed);
            repo.deleteById(e.id);
            throw ex;
        }

        // 2) Écriture EN PREMIER : échoue tôt (période close, pièce déséquilibrée)
        //    avant tout mouvement de stock. En cas d'échec, on recrédite l'avance.
        List<AccountingService.PurchaseLeg> credits = new java.util.ArrayList<>();
        if (delegate != null) {
            credits.add(new AccountingService.PurchaseLeg(
                    prefs.collectorAdvanceAccount(),
                    "Apurement délégué " + delegate.name, paid));
        } else {
            credits.add(new AccountingService.PurchaseLeg(
                    accounting.treasuryAccountFor(p.paymentMethod()),
                    "Règlement achat " + e.ref, paid));
        }
        if (creditImputed.signum() > 0) {
            credits.add(new AccountingService.PurchaseLeg(
                    prefs.memberCreditAccount(),
                    "Remboursement crédit " + e.producerName, creditImputed));
        }
        // Reliquat : la coopérative doit encore. À qui, dépend de qui a
        // apporté la matière. Le délégué a déjà payé le producteur sur son
        // avance ; c'est lui le créancier, et c'est son compte que le
        // règlement viendra solder.
        BigDecimal remainder = amount.subtract(paid).subtract(creditImputed);
        if (remainder.signum() > 0) {
            credits.add(delegate != null
                    ? new AccountingService.PurchaseLeg(prefs.delegatePayableAccount(),
                            "Reliquat dû à " + delegate.name, remainder)
                    : new AccountingService.PurchaseLeg(prefs.producerPayableAccount(),
                            "Reliquat dû à " + e.producerName, remainder));
        }
        AccountingService.PurchaseLeg marginCharge = null;
        AccountingService.PurchaseLeg marginCredit = null;
        if (margin.signum() > 0) {
            marginCharge = new AccountingService.PurchaseLeg(
                    prefs.delegateMarginAccount(), "Rémunération délégué " + delegate.name, margin);
            marginCredit = new AccountingService.PurchaseLeg(
                    prefs.collectorAdvanceAccount(), "Rémunération délégué " + delegate.name, margin);
        }
        try {
            accounting.postFromProducerPurchase(e.id, e.ref, article.id, parseType(article.type),
                            article.name, amount, p.date(), credits, marginCharge, marginCredit)
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        } catch (RuntimeException ex) {
            if (advance != null) advances.creditBack(advance.id, imputed);
            for (int i = 0; i < imputedCredits.size(); i++) {
                memberCredits.creditBack(imputedCredits.get(i).id, imputations.get(i).amountFcfa());
            }
            repo.deleteById(e.id);
            throw ex;
        }

        // 3) Entrée de stock au coût du reçu (montant ÷ poids), datée du reçu.
        //    Rattaché à un délégué, le bordereau fait lot : selon la préférence
        //    tenant, son coût s'impose au CMUP au lieu d'être pondéré.
        BigDecimal unitCost = amount.divide(weight, 6, RoundingMode.HALF_UP);
        boolean replaceCmup = delegate != null && prefs.collectorDeliveryReplacesCmup();
        String lotRef = e.deliveryRef != null ? e.deliveryRef : e.ref;
        stockService.applyMovement(new MovementInput(
                article.id, siteId, MovementKind.IN, weight, unitCost,
                MovementSource.PRODUCER_PURCHASE, e.ref, e.id, null,
                "Achat producteur " + e.producerName, null,
                p.date().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                false, lotRef, replaceCmup));
        e.movementRef = e.ref;

        // Le reçu existe déjà : on n'y ajoute que les références produites
        // en chemin (pièce comptable, mouvement de stock).
        repo.replace(e);

        // Journal des retenues, une fois la livraison acquise : elle est la
        // pièce que le producteur peut venir contester.
        for (int i = 0; i < imputedCredits.size(); i++) {
            memberCredits.recordImputation(imputedCredits.get(i), e.id, e.ref, e.date,
                    imputations.get(i).amountFcfa(), imputations.get(i).notes());
        }

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
                throw new BusinessException(Messages.msg("m.ppu-weight-from-bags-required"));
            }
            weight = prefs.producerStandardBagKg.multiply(BigDecimal.valueOf(p.nbSacs()));
        } else {
            weight = p.weightKg();
        }
        if (weight == null || weight.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.ppu-weight-required"));
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
            throw new BusinessException(Messages.msg("m.ppu-price-required"));
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
            throw new BusinessException(Messages.msg("m.ppu-amount-required"));
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
    private void ensureProducerFileUsable(TenantPreferences prefs, MemberEntity m,
                                          java.time.LocalDate operationDate) {
        if (!prefs.blockProducerPurchaseOnIncompleteFile()) return;
        int validityMonths = prefs.producerFileValidityMonths();
        // La péremption se juge à la date de l'achat, pas à celle du
        // traitement : une saisie de terrain synchronisée après l'échéance
        // du dossier reste valable si le dossier l'était au moment des
        // faits.
        MemberFileStatusDto status = MemberFileCompleteness.evaluate(m, validityMonths,
                producerRefKeys.identityProofTypeNames(),
                operationDate != null ? operationDate : java.time.LocalDate.now());
        if (status.expired()) {
            throw new BusinessException(ErrorCode.PRODUCER_FILE_INCOMPLETE,
                    Messages.msg("m.ppu-producer-file-expired", m.name, status.expiresAt()));
        }
        if (!status.missingFieldCodes().isEmpty()) {
            // Le message était traduit mais y interpolait des intitulés
            // français figés : en anglais, il sortait à moitié dans chaque
            // langue. Les champs se libellent maintenant comme la phrase
            // qui les porte.
            String fields = status.missingFieldCodes().stream()
                    .map(code -> Messages.msg(MemberFileField.valueOf(code).messageKey()))
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new BusinessException(ErrorCode.PRODUCER_FILE_INCOMPLETE,
                    Messages.msg("m.ppu-producer-file-incomplete", m.name, fields));
        }
    }

    /**
     * Code externe à recopier sur le reçu. Un producteur en cumule souvent
     * plusieurs (carte filière, programme de certification) : recopier le
     * premier venu ferait figurer un numéro que l'administration ne
     * reconnaît pas. Ordre : le code porté par le document, sinon celui du
     * type déclaré comme référence par le tenant, sinon le premier connu.
     */
    private static String externalCodeFor(MemberEntity m, String fromDocument,
                                          TenantPreferences prefs) {
        if (m.externalProducerCodes == null || m.externalProducerCodes.isEmpty()) return null;
        String provided = blankToNull(fromDocument);
        if (provided != null) {
            for (var c : m.externalProducerCodes) {
                if (c.number != null && c.number.trim().equalsIgnoreCase(provided)) {
                    return c.number.trim();
                }
            }
        }
        String referenceType = blankToNull(prefs.producerReferenceCodeType);
        if (referenceType != null) {
            for (var c : m.externalProducerCodes) {
                if (c.type != null && c.number != null && !c.number.isBlank()
                        && c.type.trim().equalsIgnoreCase(referenceType)) {
                    return c.number.trim();
                }
            }
        }
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

    /**
     * Rémunération du délégué sur ce reçu, selon le mode retenu par le
     * tenant. Le taux du délégué prime sur celui du tenant : sur le
     * terrain, deux délégués ne sont pas payés pareil.
     */

    /**
     * Montant réellement remis au producteur. Sans l'option de paiement
     * partiel, le montant dû est réputé payé : un reliquat ne peut pas
     * apparaître par simple oubli de saisie.
     */
    /**
     * Montant réellement remis au producteur, sur ce qui lui revient une
     * fois les retenues déduites. Sans l'option de paiement partiel, ce
     * solde est réputé versé : un reliquat ne peut pas apparaître par
     * simple oubli de saisie.
     */
    private static BigDecimal resolvePaid(TenantPreferences prefs, ProducerPurchaseUpsertDto p,
                                          BigDecimal payable) {
        if (!prefs.producerPartialPaymentEnabled() || p.amountPaidFcfa() == null) return payable;
        BigDecimal paid = p.amountPaidFcfa();
        if (paid.signum() < 0) {
            throw new BusinessException(Messages.msg("m.ppu-paid-negative"));
        }
        if (paid.compareTo(payable) > 0) {
            throw new BusinessException(Messages.msg("m.ppu-paid-exceeds-payable",
                    String.valueOf(paid), String.valueOf(payable)));
        }
        return paid;
    }


    // ─── Annulation (contre-passation) ─────────────────────────────

    /**
     * Contre-passe un reçu d'achat producteur.
     *
     * <p>Le reçu ne se modifie pas : il est déjà entré en stock, a fixé le
     * coût moyen et produit une écriture. On l'annule, et on ressaisit.
     * C'est aussi ce que l'aide en ligne promet depuis toujours.</p>
     *
     * <p>Trois refus plutôt qu'un forçage. Un reçu déjà réglé, une matière
     * déjà sortie du stock, une annulation déjà faite : dans les trois cas
     * l'annulation est refusée avec un motif lisible, au lieu de laisser
     * un stock négatif ou un règlement orphelin. Sur la seule voie
     * d'entrée matière d'une coopérative, un stock négatif silencieux
     * n'est pas défendable.</p>
     *
     * <p>Ordre : le statut d'abord, en écriture conditionnelle, pour que le
     * perdant d'une double annulation soit arrêté avant tout effet de
     * bord. Puis stock, avance, retenues, comptabilité.</p>
     */
    public ProducerPurchaseResponseDto cancel(UUID id, String reason) {
        ProducerPurchaseEntity e = loadOrFail(id);
        if (e.isCancelled()) {
            throw new BusinessException(Messages.msg("m.ppu-already-cancelled", e.ref));
        }

        List<ProducerPaymentEntity> payments = producerPayments.listForPurchase(id);
        if (!payments.isEmpty()) {
            throw new BusinessException(Messages.msg("m.ppu-cancel-settled", e.ref,
                    payments.get(0).ref));
        }

        BigDecimal weight = nz(e.weightKg);
        if (e.articleId != null && e.siteId != null && weight.signum() > 0) {
            BigDecimal available = stockItems.findByArticleAndSite(e.articleId, e.siteId)
                    .map(item -> item.quantity == null ? BigDecimal.ZERO : item.quantity)
                    .orElse(BigDecimal.ZERO);
            if (available.compareTo(weight) < 0) {
                throw new BusinessException(Messages.msg("m.ppu-cancel-stock-consumed",
                        e.ref, weight, available));
            }
        }

        if (!repo.tryCancel(id)) {
            throw new BusinessException(Messages.msg("m.ppu-already-cancelled", e.ref));
        }

        ProducerPurchaseCancellation c = new ProducerPurchaseCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledByEmail = actor();
        c.cancelledAt = Instant.now();

        // 1. Stock : sortie miroir, paire neutralisée, coût moyen recalculé.
        if (e.articleId != null && e.siteId != null && weight.signum() > 0) {
            StockMovementEntity original = stockMovements.listBySourceEntity(e.id).stream()
                    .filter(m -> m.kind == MovementKind.IN)
                    .findFirst()
                    .orElse(null);
            BigDecimal unitCost = nz(e.amountFcfa).divide(weight, 6, RoundingMode.HALF_UP);
            MovementInput compensation = new MovementInput(
                    e.articleId, e.siteId, MovementKind.OUT, weight, unitCost,
                    MovementSource.PRODUCER_PURCHASE, e.ref, e.id, null,
                    null, "Contre-passation " + e.ref + " : " + c.reason,
                    Instant.now(), false, null, false);
            if (original != null) {
                stockService.reverseEntry(original.id, compensation);
            } else {
                stockService.applyMovement(compensation);
            }
        }

        // 2. Avance du délégué : le montant imputé lui revient.
        if (e.collectorAdvanceId != null) {
            BigDecimal imputed = nz(e.amountFcfa).add(nz(e.delegateMarginFcfa));
            advances.creditBack(e.collectorAdvanceId, imputed);
            c.advanceCreditedBackFcfa = imputed;
        }

        // 3. Retenues sur crédits membres : le solde revient au membre et la
        //    ligne quitte le journal du crédit.
        BigDecimal restored = BigDecimal.ZERO;
        for (MemberCreditEntity credit : memberCredits.findImputedByPurchase(e.id)) {
            BigDecimal amount = credit.imputations == null ? BigDecimal.ZERO
                    : credit.imputations.stream()
                            .filter(i -> e.id.equals(i.purchaseId))
                            .map(i -> nz(i.amountFcfa))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.signum() > 0) {
                memberCredits.reverseImputation(credit.id, e.id, amount);
                restored = restored.add(amount);
            }
        }
        if (restored.signum() > 0) c.creditRestoredFcfa = restored;

        // 4. Comptabilité : contre-passation idempotente, no-op si la pièce
        //    d'origine était partie en quarantaine.
        accounting.reverseFrom(PostingSourceType.PRODUCER_PURCHASE, e.id, c.reason)
                .ifPresent(piece -> c.reversalPieceRef = piece.ref);

        e.status = ProducerPurchaseStatus.CANCELLED;
        e.cancellation = c;
        e.updatedAt = Instant.now();
        repo.replace(e);

        audit.event(AuditEventType.PRODUCER_PURCHASE_CANCELLED)
                .actorEmail(actor())
                .target("producer_purchase", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Contre-passation de " + e.ref + " : " + c.reason)
                .record();
        return ProducerPurchaseResponseDto.from(e);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ProducerPurchaseEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ppu-receipt-not-found", id)));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
