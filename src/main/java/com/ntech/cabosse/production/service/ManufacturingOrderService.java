package com.ntech.cabosse.production.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.production.dto.CompleteOrderDto;
import com.ntech.cabosse.production.dto.ProductionOrderResponseDto;
import com.ntech.cabosse.production.dto.ProductionOrderUpsertDto;
import com.ntech.cabosse.production.entity.ConsumptionLine;
import com.ntech.cabosse.production.entity.ManufacturingOrderCancellation;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.production.entity.StepProgress;
import com.ntech.cabosse.production.repository.ManufacturingOrderRepository;
import com.ntech.cabosse.recipe.entity.RecipeEntity;
import com.ntech.cabosse.recipe.entity.RecipeIngredient;
import com.ntech.cabosse.recipe.entity.RecipeStep;
import com.ntech.cabosse.recipe.repository.RecipeRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service métier des ordres de fabrication.
 *
 * <p>Cycle de vie : {@link OfStatus#DRAFT} → {@link OfStatus#IN_PROGRESS}
 * (consommation matières via {@link StockService}) → étapes intermédiaires
 * si la recette en définit → {@link OfStatus#COMPLETED} (entrée PF en
 * stock avec CMUP = coût matière / qté produite). La contre-passation
 * pose des mouvements compensatoires miroirs en {@code force=true} pour
 * bypasser le contrôle de négatif.</p>
 *
 * <p>Snapshots figés à la création : recette + étapes + PF + ingrédients.
 * L'OF reste lisible même si les référentiels évoluent ensuite.</p>
 */
@ApplicationScoped
public class ManufacturingOrderService {

    @Inject ManufacturingOrderRepository orders;
    @Inject com.ntech.cabosse.campaign.service.CampaignResolver campaignResolver;
    @Inject ManufacturingOrderRefService refService;
    @Inject RecipeRepository recipes;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject StockService stockService;
    @Inject StockItemRepository stockItems;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.shared.money.MoneyFormatter money;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ───────────────────────────────────────────────────

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<ProductionOrderResponseDto> list(OfStatus status, String q, UUID siteId) {
        return orders.search(status, q, siteId).stream()
                .map(ProductionOrderResponseDto::from)
                .toList();
    }

    public Pagination<ProductionOrderResponseDto> page(OfStatus status, String q, UUID siteId,
                                                       PageRequest pr) {
        long total = orders.countSearch(status, q, siteId);
        List<ProductionOrderResponseDto> items =
                orders.search(status, q, siteId, pr.skip(), pr.perPage()).stream()
                        .map(ProductionOrderResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (status != null) filters.put("status", status.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (siteId != null) filters.put("siteId", siteId.toString());
        return Pagination.of(total, pr, new String[]{"scheduledDate", "createdAt"}, "desc",
                filters, items);
    }

    public ProductionOrderResponseDto getById(UUID id) {
        return ProductionOrderResponseDto.from(loadOrFail(id));
    }

    // ─── Create (DRAFT) ────────────────────────────────────────────

    public ProductionOrderResponseDto create(ProductionOrderUpsertDto payload) {
        SiteEntity site = loadSite(payload.siteId());
        RecipeEntity recipe = loadRecipe(payload.recipeId());
        ArticleEntity fp = loadFinishedProduct(recipe.finishedProductId);

        BigDecimal ratio = payload.plannedQty().divide(recipe.yieldQty, 6, RoundingMode.HALF_UP);

        ManufacturingOrderEntity e = new ManufacturingOrderEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.nextOfRef();
        e.siteId = site.id;
        e.siteName = site.name;
        e.status = OfStatus.DRAFT;

        e.recipeId = recipe.id;
        e.recipeCode = recipe.code;
        e.recipeName = recipe.name;
        e.recipeYieldQty = recipe.yieldQty;
        e.recipeYieldUnit = recipe.yieldUnit;
        e.recipeStepsSnapshot = snapshotSteps(recipe.steps);

        e.finishedProductId = fp.id;
        e.finishedProductCode = fp.code;
        e.finishedProductName = fp.name;
        e.finishedProductUnit = fp.unit;
        e.finishedProductUnitWeightGrams = fp.unitWeightGrams;

        e.plannedQty = payload.plannedQty();
        e.scheduledDate = payload.scheduledDate();
        stampCampaign(e);
        e.lotRef = payload.lotRef() != null && !payload.lotRef().isBlank()
                ? payload.lotRef().trim()
                : refService.nextLotRef();
        e.notes = blankToNull(payload.notes());

        e.consumptionLines = buildConsumptionLines(recipe.ingredients, ratio);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        orders.insert(e);
        record(e, AuditEventType.MANUFACTURING_ORDER_CREATED, "Création");
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Update (DRAFT only) ───────────────────────────────────────

    public ProductionOrderResponseDto update(UUID id, ProductionOrderUpsertDto payload) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.DRAFT) {
            throw new BusinessException(Messages.msg("m.prd-draft-only-edit"));
        }
        // On autorise un changement de qté planifiée et de date — la
        // recette ne change pas (sinon recréer un OF, sinon on perd le
        // snapshot d'étapes).
        if (!e.recipeId.equals(payload.recipeId())) {
            throw new BusinessException(Messages.msg("m.prd-recipe-change-forbidden"));
        }
        if (!e.siteId.equals(payload.siteId())) {
            throw new BusinessException(Messages.msg("m.prd-site-change-forbidden"));
        }
        RecipeEntity recipe = loadRecipe(payload.recipeId());
        BigDecimal ratio = payload.plannedQty().divide(recipe.yieldQty, 6, RoundingMode.HALF_UP);
        e.plannedQty = payload.plannedQty();
        e.scheduledDate = payload.scheduledDate();
        stampCampaign(e);
        if (payload.lotRef() != null && !payload.lotRef().isBlank()) {
            e.lotRef = payload.lotRef().trim();
        }
        e.notes = blankToNull(payload.notes());
        e.consumptionLines = buildConsumptionLines(recipe.ingredients, ratio);
        e.updatedAt = Instant.now();
        orders.replace(e);
        record(e, AuditEventType.MANUFACTURING_ORDER_UPDATED, "Modification");
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Start (DRAFT → IN_PROGRESS) ───────────────────────────────

    public ProductionOrderResponseDto start(UUID id) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.DRAFT) {
            throw new BusinessException(Messages.msg("m.prd-already-started", e.status));
        }

        // Vérif disponibilité matières — bloquante ou non selon la préférence
        // tenant. Désactivée, la production passe et les sorties se font en
        // force (stock négatif traçable).
        boolean blockOnShortage = preferencesLookup.current().blockProductionOnStockShortage();
        if (blockOnShortage) {
            checkMaterialAvailability(e);
        }

        // Snapshot des CMUP courants (avant toute consommation)
        BigDecimal totalCost = BigDecimal.ZERO;
        Instant when = Instant.now();
        for (ConsumptionLine line : e.consumptionLines) {
            BigDecimal cmupNow = stockItems.findByArticleAndSite(line.articleId, e.siteId)
                    .map(it -> it.cmupFcfa)
                    .orElse(BigDecimal.ZERO);
            line.cmupAtConsumptionFcfa = cmupNow;
            line.totalCostFcfa = line.consumedQty
                    .multiply(cmupNow)
                    .setScale(4, RoundingMode.HALF_UP);
            totalCost = totalCost.add(line.totalCostFcfa);
        }
        e.totalMaterialCostFcfa = totalCost;
        e.startedAt = when;
        e.status = OfStatus.IN_PROGRESS;

        // Première étape si recette structurée
        if (!e.recipeStepsSnapshot.isEmpty()) {
            e.currentStepIndex = 0;
            RecipeStep first = e.recipeStepsSnapshot.get(0);
            StepProgress sp = new StepProgress();
            sp.stepOrder = first.order;
            sp.stepName = first.name;
            sp.startedAt = when;
            e.stepHistory.add(sp);
        }

        // Transition d'abord (replace versionné) : le perdant d'un double
        // démarrage prend un 409 AVANT toute consommation de matières
        // (règle concurrence, patron PurchaseOrderService.deliver).
        e.updatedAt = Instant.now();
        orders.replace(e);

        for (ConsumptionLine line : e.consumptionLines) {
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.OUT,
                    line.consumedQty, line.cmupAtConsumptionFcfa,
                    MovementSource.PRODUCTION, e.ref, e.id,
                    null, "Consommation OF " + e.ref,
                    null, when, /* force */ !blockOnShortage
            ));
        }
        record(e, AuditEventType.MANUFACTURING_ORDER_STARTED,
                "Démarrage : " + money.format(totalCost, tenantContext.currency()) + " matières consommées");
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Advance step ──────────────────────────────────────────────

    public ProductionOrderResponseDto advanceStep(UUID id, String notes) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.IN_PROGRESS) {
            throw new BusinessException(Messages.msg("m.prd-advance-status", e.status));
        }
        if (e.recipeStepsSnapshot.isEmpty()) {
            throw new BusinessException(Messages.msg("m.prd-recipe-no-steps"));
        }
        if (e.currentStepIndex == null) {
            throw new BusinessException(Messages.msg("m.prd-no-current-step"));
        }
        int last = e.recipeStepsSnapshot.size() - 1;
        if (e.currentStepIndex >= last) {
            throw new BusinessException(Messages.msg("m.prd-last-step"));
        }

        Instant when = Instant.now();
        // Clore l'étape courante
        StepProgress current = lastStepProgress(e);
        if (current != null && current.completedAt == null) {
            current.completedAt = when;
            current.notes = blankToNull(notes);
        }
        // Avancer
        int next = e.currentStepIndex + 1;
        e.currentStepIndex = next;
        RecipeStep nextStep = e.recipeStepsSnapshot.get(next);
        StepProgress sp = new StepProgress();
        sp.stepOrder = nextStep.order;
        sp.stepName = nextStep.name;
        sp.startedAt = when;
        e.stepHistory.add(sp);
        e.updatedAt = when;

        orders.replace(e);
        record(e, AuditEventType.MANUFACTURING_ORDER_STEP_ADVANCED,
                "Étape suivante : " + nextStep.name);
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Complete (IN_PROGRESS → COMPLETED) ────────────────────────

    public ProductionOrderResponseDto complete(UUID id, CompleteOrderDto payload) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.IN_PROGRESS) {
            throw new BusinessException(Messages.msg("m.prd-not-started", e.status));
        }
        BigDecimal producedQty = payload.producedQty();
        if (producedQty == null || producedQty.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.prd-produced-qty-required"));
        }

        Instant when = Instant.now();
        // Clore la dernière étape si applicable
        if (!e.recipeStepsSnapshot.isEmpty()) {
            StepProgress last = lastStepProgress(e);
            if (last != null && last.completedAt == null) {
                last.completedAt = when;
                last.notes = blankToNull(payload.notes());
            }
        }

        // CMUP du PF = coût matière total / qté produite
        BigDecimal cmupPF = e.totalMaterialCostFcfa
                .divide(producedQty, 4, RoundingMode.HALF_UP);
        e.producedQty = producedQty;
        e.cmupAtCompletionFcfa = cmupPF;

        // KPIs production (optionnels — alimentent les rapports)
        e.actualDurationHours = payload.actualDurationHours();
        e.operatorsCount = payload.operatorsCount();

        // Transition d'abord (replace versionné) : une double clôture ne
        // doit jamais produire deux entrées de PF en stock.
        e.completedAt = when;
        e.status = OfStatus.COMPLETED;
        e.updatedAt = when;
        orders.replace(e);

        // Entrée PF en stock
        stockService.applyMovement(new MovementInput(
                e.finishedProductId, e.siteId,
                MovementKind.IN,
                producedQty, cmupPF,
                MovementSource.PRODUCTION, e.ref, e.id,
                null, "Production " + e.ref,
                null, when, false, e.lotRef
        ));

        record(e, AuditEventType.MANUFACTURING_ORDER_COMPLETED,
                "Terminé : " + producedQty + " " + e.finishedProductUnit
                        + " · CMUP " + money.format(cmupPF, tenantContext.currency()) + " · lot " + e.lotRef);
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Cancel (contre-passation) ─────────────────────────────────

    public ProductionOrderResponseDto cancel(UUID id, String reason) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status == OfStatus.CANCELLED) {
            throw new BusinessException(Messages.msg("m.prd-already-cancelled"));
        }
        OfStatus previous = e.status;
        Instant when = Instant.now();

        // Transition d'abord (replace versionné) : le perdant d'une double
        // annulation prend un 409 avant toute compensation de stock.
        ManufacturingOrderCancellation c = new ManufacturingOrderCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledByEmail = actor();
        c.cancelledAt = when;
        c.previousStatus = previous;
        e.cancellation = c;
        e.status = OfStatus.CANCELLED;
        e.updatedAt = when;
        orders.replace(e);

        // DRAFT : aucun impact stock → rien à compenser
        // IN_PROGRESS : poser IN compensatoires sur matières
        // COMPLETED : idem + poser OUT compensatoire sur PF
        if (previous == OfStatus.IN_PROGRESS || previous == OfStatus.COMPLETED) {
            for (ConsumptionLine line : e.consumptionLines) {
                stockService.applyMovement(new MovementInput(
                        line.articleId, e.siteId,
                        MovementKind.IN,
                        line.consumedQty, line.cmupAtConsumptionFcfa,
                        MovementSource.PRODUCTION, e.ref, e.id,
                        null, "Contre-passation OF " + e.ref + " : " + reason,
                        null, when, true
                ));
            }
        }
        if (previous == OfStatus.COMPLETED && e.producedQty != null) {
            stockService.applyMovement(new MovementInput(
                    e.finishedProductId, e.siteId,
                    MovementKind.OUT,
                    e.producedQty, e.cmupAtCompletionFcfa,
                    MovementSource.PRODUCTION, e.ref, e.id,
                    null, "Contre-passation OF " + e.ref + " : " + reason,
                    null, when, true, e.lotRef
            ));
        }

        record(e, AuditEventType.MANUFACTURING_ORDER_CANCELLED,
                "Contre-passation depuis " + previous + " : " + c.reason);
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private void checkMaterialAvailability(ManufacturingOrderEntity e) {
        List<String> missing = new ArrayList<>();
        for (ConsumptionLine line : e.consumptionLines) {
            Optional<StockItemEntity> stock =
                    stockItems.findByArticleAndSite(line.articleId, e.siteId);
            BigDecimal available = stock.map(it -> it.quantity).orElse(BigDecimal.ZERO);
            if (available.compareTo(line.consumedQty) < 0) {
                BigDecimal shortQty = line.consumedQty.subtract(available);
                missing.add(line.articleName + " : manque "
                        + shortQty + " " + line.articleUnit
                        + " (dispo " + available + ", requis " + line.consumedQty + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(Messages.msg("m.prd-insufficient-materials", String.join(" ; ", missing)));
        }
    }

    // ─── Create depuis import (sans recette) ───────────────────────

    /**
     * Création d'OF sans recette préalable — cas typique de l'import
     * historique (le client a déjà fabriqué, on saisit la consommation
     * directement ligne par ligne). L'OF est toujours créé en
     * {@link OfStatus#DRAFT} : il faut ensuite transitionner manuellement
     * via {@code start} / {@code complete} pour générer les mouvements
     * stock — ces transitions exigent du stock disponible sur les matières
     * (sauf {@code allowNegativeStock} activé au tenant).
     *
     * @param siteId site où la fabrication a eu lieu
     * @param finishedProductId UUID de l'article PF cible
     * @param plannedQty quantité visée de PF
     * @param scheduledDate date (optionnelle) prévue / déclarée
     * @param lotRef étiquette de lot (vide = auto LOT-YYYY-NNNN)
     * @param notes notes libres
     * @param consumptions liste explicite des consommations matière
     */
    public ProductionOrderResponseDto createFromImport(
            UUID siteId,
            UUID finishedProductId,
            BigDecimal plannedQty,
            java.time.LocalDate scheduledDate,
            String lotRef,
            String notes,
            List<ImportConsumption> consumptions) {
        if (consumptions == null || consumptions.isEmpty()) {
            throw new BusinessException(Messages.msg("m.prd-consumption-line-required"));
        }
        SiteEntity site = loadSite(siteId);
        ArticleEntity fp = loadFinishedProduct(finishedProductId);

        ManufacturingOrderEntity e = new ManufacturingOrderEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.nextOfRef();
        e.siteId = site.id;
        e.siteName = site.name;
        e.status = OfStatus.DRAFT;

        // Pas de recette → tous les snapshots recette restent null/vides.
        // L'OF n'aura pas de stepHistory non plus (transition complete directe).
        e.recipeId = null;
        e.recipeCode = null;
        e.recipeName = null;
        e.recipeYieldQty = null;
        e.recipeYieldUnit = null;
        e.recipeStepsSnapshot = new ArrayList<>();

        e.finishedProductId = fp.id;
        e.finishedProductCode = fp.code;
        e.finishedProductName = fp.name;
        e.finishedProductUnit = fp.unit;
        e.finishedProductUnitWeightGrams = fp.unitWeightGrams;

        e.plannedQty = plannedQty;
        e.scheduledDate = scheduledDate;
        stampCampaign(e);
        e.lotRef = (lotRef != null && !lotRef.isBlank())
                ? lotRef.trim()
                : refService.nextLotRef();
        e.notes = blankToNull(notes);

        e.consumptionLines = buildConsumptionLinesFromInput(consumptions);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        orders.insert(e);
        record(e, AuditEventType.MANUFACTURING_ORDER_CREATED,
                "Création depuis import (sans recette)");
        return ProductionOrderResponseDto.from(e);
    }

    /** Paramètre {@link #createFromImport} — 1 consommation matière. */
    public record ImportConsumption(UUID articleId, BigDecimal plannedQty) {}

    private List<ConsumptionLine> buildConsumptionLinesFromInput(List<ImportConsumption> input) {
        List<ConsumptionLine> lines = new ArrayList<>();
        for (ImportConsumption in : input) {
            if (in == null || in.articleId() == null) continue;
            ArticleEntity article = articles.findById(in.articleId()).orElseThrow(
                    () -> new BusinessException(Messages.msg("m.prd-article-not-found", in.articleId()))
            );
            ConsumptionLine line = new ConsumptionLine();
            line.id = UuidCreator.getTimeOrderedEpoch();
            line.articleId = article.id;
            line.articleCode = article.code;
            line.articleName = article.name;
            line.articleUnit = article.unit;
            line.plannedQty = in.plannedQty();
            line.consumedQty = in.plannedQty();
            lines.add(line);
        }
        return lines;
    }

    private List<ConsumptionLine> buildConsumptionLines(List<RecipeIngredient> ingredients,
                                                         BigDecimal ratio) {
        List<ConsumptionLine> lines = new ArrayList<>();
        if (ingredients == null) return lines;
        for (RecipeIngredient ing : ingredients) {
            ArticleEntity article = articles.findById(ing.articleId).orElseThrow(
                    () -> new BusinessException(Messages.msg("m.prd-article-not-found", ing.articleId))
            );
            ConsumptionLine line = new ConsumptionLine();
            line.id = UuidCreator.getTimeOrderedEpoch();
            line.articleId = article.id;
            line.articleCode = article.code;
            line.articleName = article.name;
            line.articleUnit = article.unit;
            line.plannedQty = ing.quantity.multiply(ratio).setScale(4, RoundingMode.HALF_UP);
            line.consumedQty = line.plannedQty; // v1 : pas d'ajustement entre planifié et consommé
            lines.add(line);
        }
        return lines;
    }

    private List<RecipeStep> snapshotSteps(List<RecipeStep> source) {
        List<RecipeStep> snapshot = new ArrayList<>();
        if (source == null) return snapshot;
        for (RecipeStep s : source) {
            RecipeStep copy = new RecipeStep();
            copy.order = s.order;
            copy.name = s.name;
            copy.description = s.description;
            copy.expectedDurationMinutes = s.expectedDurationMinutes;
            snapshot.add(copy);
        }
        return snapshot;
    }

    private StepProgress lastStepProgress(ManufacturingOrderEntity e) {
        if (e.stepHistory == null || e.stepHistory.isEmpty()) return null;
        return e.stepHistory.get(e.stepHistory.size() - 1);
    }

    private ManufacturingOrderEntity loadOrFail(UUID id) {
        return orders.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.prd-of-not-found", id)));
    }

    private RecipeEntity loadRecipe(UUID id) {
        RecipeEntity r = recipes.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.prd-recipe-not-found", id))
        );
        if (!r.active) {
            throw new BusinessException(Messages.msg("m.prd-recipe-disabled", r.name));
        }
        return r;
    }

    private ArticleEntity loadFinishedProduct(UUID id) {
        ArticleEntity a = articles.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.prd-finished-product-not-found", id))
        );
        if (!ArticleType.FINISHED_PRODUCT.name().equals(a.type)) {
            throw new BusinessException(Messages.msg("m.prd-target-not-finished-product", a.name));
        }
        if (!a.active) {
            throw new BusinessException(Messages.msg("m.prd-finished-product-disabled", a.name));
        }
        if (!a.stockable) {
            throw new BusinessException(Messages.msg("m.prd-finished-product-not-stockable", a.name));
        }
        return a;
    }

    private SiteEntity loadSite(UUID id) {
        SiteEntity s = sites.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.prd-site-not-found", id))
        );
        if (!s.active) {
            throw new BusinessException(Messages.msg("m.prd-site-disabled", s.name));
        }
        return s;
    }

    private void record(ManufacturingOrderEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("manufacturing_order", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description + " : OF " + e.ref
                        + " (" + e.finishedProductName + ", "
                        + e.plannedQty + " " + e.finishedProductUnit + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Rattache l'opération à la campagne de sa date métier.
     *
     * <p>Appelé aussi à la modification : corriger la date d'une opération
     * doit déplacer son rattachement, sinon un correctif la laisse comptée
     * dans la campagne d'origine.</p>
     */
    private void stampCampaign(ManufacturingOrderEntity e) {
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.scheduledDate, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
    }

}
