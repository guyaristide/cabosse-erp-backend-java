package com.ntech.cabosse.stock.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.dto.StockItemResponseDto;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.repository.StockMovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cœur du module Stock : applique tout mouvement (entrée, sortie,
 * ajustement, transfert, amorçage) en garantissant le recalcul atomique
 * du CMUP par couple {@code (articleId, siteId)}.
 *
 * <p>Le recalcul du CMUP utilise un <strong>pipeline d'agrégation</strong>
 * MongoDB ({@code findOneAndUpdate} avec étapes {@code $set} référençant
 * les valeurs courantes). Cela rend l'opération indivisible sans
 * nécessiter de transaction multi-document — donc sans replica set.</p>
 *
 * <p>Tous les autres services qui génèrent des mouvements (RD, BC, OF,
 * vente, transfert, inventaire physique) passent par
 * {@link #applyMovement(MovementInput)}.</p>
 */
@ApplicationScoped
public class StockService {

    /**
     * Drapeau v1 — autorise ou non un stock à devenir négatif sur une
     * sortie. Sera lu depuis les settings tenant à terme. Défaut :
     * {@code false} (bloque les sorties qui mettraient le stock dans le
     * rouge). Les {@link MovementKind#ADJUSTMENT} ne sont jamais
     * concernés par ce drapeau (par définition, un comptage peut
     * révéler un déficit qu'il faut acter).
     */
    private static final boolean ALLOW_NEGATIVE_STOCK = false;

    /** Précision du CMUP en BD (4 décimales — suffit pour FCFA). */
    private static final int CMUP_SCALE = 4;

    /** Précision des quantités persistées. */
    private static final int QTY_SCALE = 4;

    /** Précision des montants totaux persistés. */
    private static final int MONEY_SCALE = 4;

    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject StockItemRepository stockItems;
    @Inject StockMovementRepository movements;
    @Inject StockMovementRefService refService;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public StockItemResponseDto getByArticleAndSite(UUID articleId, UUID siteId) {
        StockItemEntity e = stockItems.findByArticleAndSite(articleId, siteId)
                .orElseThrow(() -> new NotFoundException(
                        "Aucun stock pour cet article sur ce site."));
        return StockItemResponseDto.from(e);
    }

    public List<StockItemResponseDto> listBySite(UUID siteId, String q,
                                                  ArticleType articleType,
                                                  boolean belowThresholdOnly) {
        return stockItems.listBySite(siteId, q, articleType, belowThresholdOnly).stream()
                .map(StockItemResponseDto::from)
                .toList();
    }

    public List<StockItemResponseDto> listByArticle(UUID articleId) {
        return stockItems.listByArticle(articleId).stream()
                .map(StockItemResponseDto::from)
                .toList();
    }

    // ─── Écriture — point d'entrée unique ───────────────────────────

    /**
     * Applique un mouvement de stock et retourne la position
     * <strong>post-mouvement</strong> du couple {@code (article, site)}.
     */
    public StockItemResponseDto applyMovement(MovementInput input) {
        // ── Validations ──
        if (input.articleId() == null) throw new BusinessException("Article requis.");
        if (input.siteId() == null) throw new BusinessException("Site requis.");
        if (input.kind() == null) throw new BusinessException("Type de mouvement requis.");
        if (input.quantity() == null) throw new BusinessException("Quantité requise.");
        if (input.kind() == MovementKind.ADJUSTMENT
                && (input.reason() == null || input.reason().isBlank())) {
            throw new BusinessException("Motif obligatoire pour un ajustement.");
        }

        ArticleEntity article = loadArticle(input.articleId());
        SiteEntity site = loadSite(input.siteId());

        // Quantité signée selon le type
        BigDecimal signedQty = switch (input.kind()) {
            case IN, OPENING, TRANSFER_IN -> input.quantity().abs();
            case OUT, TRANSFER_OUT -> input.quantity().abs().negate();
            case ADJUSTMENT -> input.quantity(); // déjà signée
        };
        if (signedQty.signum() == 0) {
            throw new BusinessException("Quantité non nulle requise.");
        }

        // OPENING refusé si le couple a déjà un mouvement (un site ne
        // s'amorce qu'une fois — utiliser ADJUSTMENT sinon).
        if (input.kind() == MovementKind.OPENING
                && movements.hasAnyMovement(input.articleId(), input.siteId())) {
            throw new BusinessException(
                    "Amorçage refusé — ce couple (article, site) a déjà des mouvements. "
                            + "Utilisez un ajustement.");
        }

        // OUT/TRANSFER_OUT : vérifier la disponibilité si on bloque les
        // négatifs. Le drapeau {@code force} de l'input bypasse ce contrôle
        // — réservé aux contre-passations qui restaurent la cohérence avec
        // la transaction d'origine, quitte à exposer un stock négatif si la
        // marchandise a déjà été consommée ou transférée entre-temps.
        if ((input.kind() == MovementKind.OUT
                || input.kind() == MovementKind.TRANSFER_OUT)
                && !ALLOW_NEGATIVE_STOCK
                && !input.force()) {
            BigDecimal current = stockItems.findByArticleAndSite(input.articleId(), input.siteId())
                    .map(e -> e.quantity)
                    .orElse(BigDecimal.ZERO);
            BigDecimal needed = signedQty.abs();
            if (current.compareTo(needed) < 0) {
                throw new BusinessException(
                        "Stock insuffisant : "
                                + current + " " + article.unit + " disponible, "
                                + needed + " demandé(s).");
            }
        }

        // ── Application atomique ──
        boolean isEntry = isEntryKind(input.kind());
        BigDecimal puIn = input.unitPriceFcfa() != null
                ? input.unitPriceFcfa()
                : BigDecimal.ZERO;

        StockItemEntity updated = isEntry
                ? applyEntryAtomic(article, site, signedQty, puIn)
                : applyOutOrAdjustmentAtomic(article, site, signedQty);

        // ── Journalisation du mouvement ──
        StockMovementEntity mvt = new StockMovementEntity();
        mvt.id = UuidCreator.getTimeOrderedEpoch();
        mvt.ref = refService.next();
        mvt.articleId = article.id;
        mvt.siteId = site.id;
        mvt.articleCode = article.code;
        mvt.articleName = article.name;
        mvt.articleUnit = article.unit;
        mvt.siteName = site.name;
        mvt.kind = input.kind();
        mvt.quantitySigned = bounded(signedQty, QTY_SCALE);
        // Le PU mémorisé sur le mvt :
        //   - entrée : PU d'achat fourni
        //   - sortie : CMUP courant snapshoté (= cmupAfter ici car
        //     la sortie ne change pas le CMUP)
        //   - ajustement : null (pas de valorisation propre)
        if (input.kind() == MovementKind.ADJUSTMENT) {
            mvt.unitPriceFcfa = null;
        } else if (isEntry) {
            mvt.unitPriceFcfa = bounded(puIn, CMUP_SCALE);
        } else {
            mvt.unitPriceFcfa = bounded(updated.cmupFcfa, CMUP_SCALE);
        }
        mvt.totalFcfa = (mvt.unitPriceFcfa != null)
                ? bounded(signedQty.abs().multiply(mvt.unitPriceFcfa), MONEY_SCALE)
                : null;
        mvt.quantityAfter = bounded(updated.quantity, QTY_SCALE);
        mvt.cmupAfterFcfa = bounded(updated.cmupFcfa, CMUP_SCALE);
        mvt.sourceType = input.sourceType();
        mvt.sourceRef = input.sourceRef();
        mvt.sourceEntityId = input.sourceEntityId();
        mvt.transferId = input.transferId();
        mvt.reason = input.reason();
        mvt.lotRef = input.lotRef();
        mvt.notes = input.notes();
        mvt.actorEmail = actor();
        mvt.occurredAt = input.occurredAt() != null ? input.occurredAt() : Instant.now();
        mvt.createdAt = Instant.now();
        movements.insert(mvt);

        recordMovementAudit(mvt, updated);
        return StockItemResponseDto.from(updated);
    }

    private void recordMovementAudit(StockMovementEntity mvt, StockItemEntity item) {
        AuditEventType type = switch (mvt.kind) {
            case OPENING -> AuditEventType.STOCK_OPENING_RECORDED;
            case ADJUSTMENT -> AuditEventType.STOCK_ADJUSTMENT_RECORDED;
            case TRANSFER_OUT, TRANSFER_IN -> AuditEventType.STOCK_TRANSFER_RECORDED;
            default -> AuditEventType.STOCK_MOVEMENT_RECORDED;
        };
        String description = mvt.kind.name() + " " + mvt.articleName
                + " · " + mvt.quantitySigned + " " + mvt.articleUnit
                + " · CMUP après " + (mvt.cmupAfterFcfa != null ? mvt.cmupAfterFcfa : "—")
                + (mvt.sourceRef != null ? " · source " + mvt.sourceRef : "");
        audit.event(type)
                .actorEmail(actor())
                .target("stock_movement", mvt.id.toString(), mvt.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    // ─── Pipeline d'agrégation : entrée (recalcul CMUP) ─────────────

    /**
     * Applique une entrée (IN / OPENING / TRANSFER_IN) en upsert avec
     * recalcul atomique du CMUP via pipeline d'agrégation. Le serveur
     * Mongo lit lui-même {@code quantity} et {@code cmupFcfa} courants,
     * applique la formule {@code (q*c + qi*pi) / (q + qi)}, et écrit
     * le résultat — le tout en une opération indivisible.
     */
    private StockItemEntity applyEntryAtomic(ArticleEntity article, SiteEntity site,
                                              BigDecimal qtyIn, BigDecimal puIn) {
        Instant now = Instant.now();
        UUID newDocId = UuidCreator.getTimeOrderedEpoch();
        Decimal128 qtyInD = new Decimal128(qtyIn);
        Decimal128 puInD = new Decimal128(puIn);
        Decimal128 zero = new Decimal128(BigDecimal.ZERO);

        ArticleType articleType;
        try {
            articleType = ArticleType.valueOf(article.type);
        } catch (Exception ex) {
            throw new BusinessException(
                    "Type d'article « " + article.type + " » non reconnu.");
        }

        // Stage 1 : recalcule quantity + cmupFcfa, met à jour snapshots
        // article/site et metadata. Référence aux champs courants via
        // "$quantity" / "$cmupFcfa" (résolus par Mongo après lookup).
        Document setComputed = new Document()
                .append("articleId", article.id)
                .append("siteId", site.id)
                .append("articleCode", article.code)
                .append("articleName", article.name)
                .append("articleUnit", article.unit)
                .append("articleType", articleType.name())
                .append("quantity", new Document("$add", List.of(
                        new Document("$ifNull", List.of("$quantity", zero)),
                        qtyInD
                )))
                .append("cmupFcfa", cmupExpression(qtyInD, puInD, zero))
                .append("lastMovementAt", now)
                .append("updatedAt", now)
                .append("version", new Document("$add", List.of(
                        new Document("$ifNull", List.of("$version", 0L)),
                        1L
                )));

        // Stage 2 : valeurs par défaut à la création (upsert)
        Document setDefaults = new Document()
                .append("_id", new Document("$ifNull", List.of("$_id", newDocId)))
                .append("createdAt", new Document("$ifNull", List.of("$createdAt", now)))
                .append("alertThreshold", new Document("$ifNull", java.util.Arrays.asList(
                        "$alertThreshold",
                        // Arrays.asList accepte les null — contrairement à List.of qui
                        // jette NPE. Un article sans seuil saisi (typiquement auto-créé
                        // à l'import BC) doit pouvoir entrer en stock sans planter.
                        article.alertThreshold != null
                                ? new Decimal128(article.alertThreshold)
                                : null
                )));

        List<Bson> pipeline = List.of(
                new Document("$set", setComputed),
                new Document("$set", setDefaults)
        );

        StockItemEntity result = stockItems.collection().findOneAndUpdate(
                Filters.and(
                        Filters.eq("articleId", article.id),
                        Filters.eq("siteId", site.id)
                ),
                pipeline,
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );

        if (result == null) {
            throw new BusinessException(
                    "Échec de l'application du mouvement sur le stock.");
        }
        return result;
    }

    /**
     * Construit l'expression Mongo qui calcule le nouveau CMUP :
     * <pre>
     *   newCmup = (oldQty * oldCmup + qtyIn * puIn) / (oldQty + qtyIn)
     *           = 0 si (oldQty + qtyIn) &lt;= 0
     * </pre>
     * Utilise {@code $let} pour réutiliser les valeurs et garder
     * l'expression lisible.
     */
    private static Document cmupExpression(Decimal128 qtyInD, Decimal128 puInD, Decimal128 zero) {
        Document innerVars = new Document()
                .append("newQty", new Document("$add", List.of("$$oldQty", "$$inQty")));

        // Le $divide peut produire un décimal non terminant (ex. 264200/7).
        // On le borne à CMUP_SCALE décimales via $round, sinon le résultat
        // hérite de toute la précision de Decimal128 (34 chiffres) et déborde
        // dès qu'on le multiplie en aval (totalFcfa) → erreur d'encodage BSON.
        Document innerIn = new Document("$round", List.of(
                new Document("$cond", new Document()
                        .append("if", new Document("$lte", List.of("$$newQty", zero)))
                        .append("then", zero)
                        .append("else", new Document("$divide", List.of(
                                new Document("$add", List.of(
                                        new Document("$multiply", List.of("$$oldQty", "$$oldCmup")),
                                        new Document("$multiply", List.of("$$inQty", "$$inPu"))
                                )),
                                "$$newQty"
                        )))
                ),
                CMUP_SCALE
        ));

        Document outerVars = new Document()
                .append("oldQty", new Document("$ifNull", List.of("$quantity", zero)))
                .append("oldCmup", new Document("$ifNull", List.of("$cmupFcfa", zero)))
                .append("inQty", qtyInD)
                .append("inPu", puInD);

        return new Document("$let", new Document()
                .append("vars", outerVars)
                .append("in", new Document("$let", new Document()
                        .append("vars", innerVars)
                        .append("in", innerIn)
                ))
        );
    }

    // ─── Sortie / ajustement : qté modifiée, CMUP inchangé ──────────

    private StockItemEntity applyOutOrAdjustmentAtomic(ArticleEntity article, SiteEntity site,
                                                        BigDecimal signedQty) {
        Instant now = Instant.now();
        UUID newDocId = UuidCreator.getTimeOrderedEpoch();
        Decimal128 deltaD = new Decimal128(signedQty);
        Decimal128 zero = new Decimal128(BigDecimal.ZERO);

        ArticleType articleType;
        try {
            articleType = ArticleType.valueOf(article.type);
        } catch (Exception ex) {
            throw new BusinessException(
                    "Type d'article « " + article.type + " » non reconnu.");
        }

        Document setComputed = new Document()
                .append("articleId", article.id)
                .append("siteId", site.id)
                .append("articleCode", article.code)
                .append("articleName", article.name)
                .append("articleUnit", article.unit)
                .append("articleType", articleType.name())
                .append("quantity", new Document("$add", List.of(
                        new Document("$ifNull", List.of("$quantity", zero)),
                        deltaD
                )))
                // CMUP inchangé : on garde la valeur courante (0 si absent)
                .append("cmupFcfa", new Document("$ifNull", List.of("$cmupFcfa", zero)))
                .append("lastMovementAt", now)
                .append("updatedAt", now)
                .append("version", new Document("$add", List.of(
                        new Document("$ifNull", List.of("$version", 0L)),
                        1L
                )));

        Document setDefaults = new Document()
                .append("_id", new Document("$ifNull", List.of("$_id", newDocId)))
                .append("createdAt", new Document("$ifNull", List.of("$createdAt", now)))
                .append("alertThreshold", new Document("$ifNull", java.util.Arrays.asList(
                        "$alertThreshold",
                        // Arrays.asList accepte les null — contrairement à List.of qui
                        // jette NPE. Un article sans seuil saisi (typiquement auto-créé
                        // à l'import BC) doit pouvoir entrer en stock sans planter.
                        article.alertThreshold != null
                                ? new Decimal128(article.alertThreshold)
                                : null
                )));

        List<Bson> pipeline = List.of(
                new Document("$set", setComputed),
                new Document("$set", setDefaults)
        );

        StockItemEntity result = stockItems.collection().findOneAndUpdate(
                Filters.and(
                        Filters.eq("articleId", article.id),
                        Filters.eq("siteId", site.id)
                ),
                pipeline,
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );

        if (result == null) {
            throw new BusinessException(
                    "Échec de l'application du mouvement sur le stock.");
        }
        return result;
    }

    // ─── Transfert inter-sites ─────────────────────────────────────

    /**
     * Transfère une quantité d'un article entre deux sites du tenant.
     * Génère une paire {@link MovementKind#TRANSFER_OUT} /
     * {@link MovementKind#TRANSFER_IN} liée par un {@code transferId} —
     * le {@code PU} de l'IN au site destination est le CMUP courant du
     * site source (le transfert ne crée pas de richesse comptable).
     */
    public TransferResult transfer(UUID articleId, UUID fromSiteId, UUID toSiteId,
                                    BigDecimal quantity, String reason, String notes,
                                    Instant occurredAt) {
        if (fromSiteId == null || toSiteId == null) {
            throw new BusinessException("Sites source et destination requis.");
        }
        if (fromSiteId.equals(toSiteId)) {
            throw new BusinessException("Site source et destination doivent différer.");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException("Quantité strictement positive requise.");
        }

        BigDecimal sourceCmup = stockItems.findByArticleAndSite(articleId, fromSiteId)
                .map(e -> e.cmupFcfa)
                .orElseThrow(() -> new BusinessException(
                        "Aucun stock à transférer sur le site source."));

        UUID transferId = UuidCreator.getTimeOrderedEpoch();
        Instant when = occurredAt != null ? occurredAt : Instant.now();

        // 1) Sortie du site source — au CMUP source (snapshot porté par mvt.unitPriceFcfa)
        StockItemResponseDto outItem = applyMovement(new MovementInput(
                articleId, fromSiteId,
                MovementKind.TRANSFER_OUT,
                quantity, sourceCmup,
                MovementSource.TRANSFER, null, null,
                transferId, reason, notes, when
        ));

        // 2) Entrée au site destination — PU = CMUP source
        StockItemResponseDto inItem;
        try {
            inItem = applyMovement(new MovementInput(
                    articleId, toSiteId,
                    MovementKind.TRANSFER_IN,
                    quantity, sourceCmup,
                    MovementSource.TRANSFER, null, null,
                    transferId, reason, notes, when
            ));
        } catch (RuntimeException ex) {
            // Compensation : remettre au source (best-effort, sans
            // déclencher d'autre erreur). En pratique, applyMovement IN
            // ne devrait échouer que sur validation article/site, pas
            // sur état de stock — donc rare.
            try {
                applyMovement(new MovementInput(
                        articleId, fromSiteId,
                        MovementKind.IN,
                        quantity, sourceCmup,
                        MovementSource.MANUAL, "Rollback transfert " + transferId, null,
                        null, "Compensation transfert échoué", null, Instant.now()
                ));
            } catch (RuntimeException ignored) { /* swallow */ }
            throw ex;
        }

        return new TransferResult(transferId, outItem, inItem);
    }

    /** Tuple retour d'un transfert. */
    public record TransferResult(UUID transferId,
                                  StockItemResponseDto sourceAfter,
                                  StockItemResponseDto destinationAfter) {}

    // ─── Amorçage initial ──────────────────────────────────────────

    /**
     * Amorce le stock initial d'un site pour N articles. Refuse les
     * lignes dont le couple (article, site) a déjà un mouvement —
     * l'amorçage est unique par couple. Les autres lignes sont posées.
     */
    public OpeningResult recordOpeningBatch(UUID siteId, List<OpeningLine> lines,
                                              Instant occurredAt) {
        if (siteId == null) throw new BusinessException("Site requis.");
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("Au moins une ligne d'amorçage requise.");
        }
        List<StockItemResponseDto> created = new ArrayList<>();
        List<OpeningRejection> rejected = new ArrayList<>();
        for (OpeningLine line : lines) {
            try {
                StockItemResponseDto r = applyMovement(new MovementInput(
                        line.articleId(), siteId,
                        MovementKind.OPENING,
                        line.quantity(), line.unitPriceFcfa(),
                        MovementSource.OPENING, null, null,
                        null, "Amorçage initial", line.notes(),
                        occurredAt != null ? occurredAt : Instant.now()
                ));
                created.add(r);
            } catch (BusinessException ex) {
                rejected.add(new OpeningRejection(line.articleId(), ex.getMessage()));
            }
        }
        return new OpeningResult(created, rejected);
    }

    public record OpeningLine(UUID articleId, BigDecimal quantity,
                                BigDecimal unitPriceFcfa, String notes) {}
    public record OpeningRejection(UUID articleId, String reason) {}
    public record OpeningResult(List<StockItemResponseDto> created,
                                  List<OpeningRejection> rejected) {}

    // ─── Inventaire physique ───────────────────────────────────────

    /**
     * Saisie d'un inventaire physique sur un site : pour chaque ligne,
     * compare la quantité comptée à la quantité théorique courante et
     * pose un {@link MovementKind#ADJUSTMENT} si l'écart est non nul.
     *
     * <p>Le motif global est repris sur chaque ajustement. Une ligne
     * sans écart ne génère aucun mouvement (silencieuse).</p>
     */
    public InventoryResult recordInventoryBatch(UUID siteId, List<InventoryLine> lines,
                                                  String reason, Instant occurredAt) {
        if (siteId == null) throw new BusinessException("Site requis.");
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("Au moins une ligne d'inventaire requise.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif d'inventaire requis.");
        }
        Instant when = occurredAt != null ? occurredAt : Instant.now();

        List<InventoryAdjustment> adjustments = new ArrayList<>();
        List<OpeningRejection> rejected = new ArrayList<>();
        int noChange = 0;

        for (InventoryLine line : lines) {
            BigDecimal theoretical = stockItems.findByArticleAndSite(line.articleId(), siteId)
                    .map(e -> e.quantity)
                    .orElse(BigDecimal.ZERO);
            BigDecimal delta = line.countedQuantity().subtract(theoretical);
            if (delta.signum() == 0) {
                noChange++;
                continue;
            }
            try {
                StockItemResponseDto after = applyMovement(new MovementInput(
                        line.articleId(), siteId,
                        MovementKind.ADJUSTMENT,
                        delta, null,
                        MovementSource.INVENTORY, null, null,
                        null, reason, line.notes(), when
                ));
                adjustments.add(new InventoryAdjustment(
                        line.articleId(), theoretical, line.countedQuantity(), delta, after
                ));
            } catch (BusinessException ex) {
                rejected.add(new OpeningRejection(line.articleId(), ex.getMessage()));
            }
        }
        return new InventoryResult(adjustments, rejected, noChange);
    }

    public record InventoryLine(UUID articleId, BigDecimal countedQuantity, String notes) {}
    public record InventoryAdjustment(UUID articleId,
                                        BigDecimal theoretical,
                                        BigDecimal counted,
                                        BigDecimal delta,
                                        StockItemResponseDto after) {}
    public record InventoryResult(List<InventoryAdjustment> adjustments,
                                    List<OpeningRejection> rejected,
                                    int noChangeCount) {}

    // ─── Photo d'inventaire à date ─────────────────────────────────

    /**
     * Reconstruit la position {@code (qty, cmup)} d'un couple
     * (article, site) à une date passée, en rejouant en sens inverse
     * les mouvements postérieurs à {@code asOf} depuis la position
     * courante.
     *
     * <p>Pour le CMUP : on reprend le CMUP du dernier mouvement
     * antérieur (snapshot {@code cmupAfterFcfa} figé sur chaque mvt).
     * Si aucun mvt antérieur, CMUP = 0.</p>
     */
    public SnapshotPoint snapshotAt(UUID articleId, UUID siteId, Instant asOf) {
        StockItemEntity current = stockItems.findByArticleAndSite(articleId, siteId)
                .orElse(null);
        BigDecimal qty = current != null ? current.quantity : BigDecimal.ZERO;
        // On part de la qté courante, on retire les delta des mvts postérieurs.
        List<StockMovementEntity> after = movements.listAfter(articleId, siteId, asOf);
        for (StockMovementEntity m : after) {
            // m.quantitySigned est ce qui a été AJOUTÉ par ce mvt — on retire
            // pour revenir en arrière.
            qty = qty.subtract(m.quantitySigned != null ? m.quantitySigned : BigDecimal.ZERO);
        }
        // Trouver le dernier mvt à <= asOf pour récupérer le CMUP figé
        BigDecimal cmup = BigDecimal.ZERO;
        if (current != null) {
            List<StockMovementEntity> page = movements.listByArticleAndSite(articleId, siteId, 1000, 0);
            // page est trié par occurredAt:-1
            for (StockMovementEntity m : page) {
                if (m.occurredAt != null && !m.occurredAt.isAfter(asOf)) {
                    cmup = m.cmupAfterFcfa != null ? m.cmupAfterFcfa : BigDecimal.ZERO;
                    break;
                }
            }
        }
        return new SnapshotPoint(articleId, siteId, asOf, qty, cmup);
    }

    /**
     * Photo d'inventaire d'un site à une date — retourne la même forme
     * que {@link #listBySite}, donc directement consommable par le
     * frontend, mais avec {@code quantity} et {@code cmupFcfa} recalés
     * à {@code asOf}.
     */
    public List<StockItemResponseDto> snapshotBySiteAsItems(UUID siteId, Instant asOf) {
        List<StockItemEntity> current = stockItems.listBySite(siteId, null, null, false);
        List<StockItemResponseDto> out = new ArrayList<>(current.size());
        for (StockItemEntity it : current) {
            SnapshotPoint p = snapshotAt(it.articleId, siteId, asOf);
            // Construit un DTO avec les snapshots article du present + qté/CMUP du passé
            StockItemEntity snap = cloneAsOf(it, p.quantity(), p.cmupFcfa());
            out.add(StockItemResponseDto.from(snap));
        }
        return out;
    }

    private static StockItemEntity cloneAsOf(StockItemEntity src, BigDecimal qty, BigDecimal cmup) {
        StockItemEntity e = new StockItemEntity();
        e.id = src.id;
        e.articleId = src.articleId;
        e.siteId = src.siteId;
        e.articleCode = src.articleCode;
        e.articleName = src.articleName;
        e.articleUnit = src.articleUnit;
        e.articleType = src.articleType;
        e.quantity = qty != null ? qty : BigDecimal.ZERO;
        e.cmupFcfa = cmup != null ? cmup : BigDecimal.ZERO;
        e.alertThreshold = src.alertThreshold;
        e.lastMovementAt = src.lastMovementAt;
        e.createdAt = src.createdAt;
        e.updatedAt = src.updatedAt;
        return e;
    }

    /**
     * Photo d'inventaire pour tous les articles d'un site à
     * {@code asOf}. Forme tuple (article, qté, CMUP). Pour la même
     * information sous forme de {@link StockItemResponseDto} (plus
     * pratique côté frontend), préférer {@link #snapshotBySiteAsItems}.
     */
    public List<SnapshotPoint> snapshotBySite(UUID siteId, Instant asOf) {
        Map<UUID, SnapshotPoint> byArticle = new LinkedHashMap<>();
        // Initialise avec l'état courant
        for (StockItemEntity it : stockItems.listBySite(siteId, null, null, false)) {
            byArticle.put(it.articleId, new SnapshotPoint(
                    it.articleId, siteId, asOf, it.quantity, it.cmupFcfa
            ));
        }
        // Rejoue en sens inverse les mvts postérieurs
        List<StockMovementEntity> after = movements.listAfterForSite(siteId, asOf);
        for (StockMovementEntity m : after) {
            SnapshotPoint p = byArticle.get(m.articleId);
            BigDecimal q = (p != null ? p.quantity() : BigDecimal.ZERO)
                    .subtract(m.quantitySigned != null ? m.quantitySigned : BigDecimal.ZERO);
            // CMUP : on garde celui du dernier mvt à <= asOf, sinon 0
            BigDecimal cmup = (p != null) ? p.cmupFcfa() : BigDecimal.ZERO;
            byArticle.put(m.articleId, new SnapshotPoint(m.articleId, siteId, asOf, q, cmup));
        }
        // Récupère le CMUP au point dans le temps pour chaque article
        // (loop léger — N appels en BD ; acceptable pour un export, à
        // optimiser plus tard avec un index/aggregation si volume grossit).
        List<SnapshotPoint> result = new ArrayList<>(byArticle.size());
        for (SnapshotPoint p : byArticle.values()) {
            SnapshotPoint refined = snapshotAt(p.articleId(), siteId, asOf);
            // refined recalcule qty proprement + récupère le CMUP au bon moment
            result.add(refined);
        }
        result.sort(Comparator.comparing(SnapshotPoint::articleId));
        return result;
    }

    public record SnapshotPoint(UUID articleId, UUID siteId, Instant asOf,
                                  BigDecimal quantity, BigDecimal cmupFcfa) {}

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Borne un {@link BigDecimal} à un nombre fixe de décimales avant
     * persistance. Indispensable : un CMUP issu d'une division non
     * terminante (ex. 264200/7) traîne sinon la pleine précision de
     * Decimal128 (34 chiffres) et son produit ({@code totalFcfa}) déborde
     * la capacité d'encodage BSON. Préserve {@code null}.
     */
    private static BigDecimal bounded(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    private static boolean isEntryKind(MovementKind k) {
        return k == MovementKind.IN
                || k == MovementKind.OPENING
                || k == MovementKind.TRANSFER_IN;
    }

    private ArticleEntity loadArticle(UUID id) {
        ArticleEntity a = articles.findById(id).orElseThrow(
                () -> new NotFoundException("Article " + id + " introuvable.")
        );
        if (!a.active) throw new BusinessException("Article « " + a.name + " » désactivé.");
        if (!a.stockable) throw new BusinessException(
                "Article « " + a.name + " » non géré en stock.");
        return a;
    }

    private SiteEntity loadSite(UUID id) {
        SiteEntity s = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        if (!s.active) throw new BusinessException("Site « " + s.name + " » désactivé.");
        return s;
    }
}
