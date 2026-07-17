package com.ntech.cabosse.production.service;

import com.github.f4b6a3.uuid.UuidCreator;
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
    @Inject ManufacturingOrderRefService refService;
    @Inject RecipeRepository recipes;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject StockService stockService;
    @Inject StockItemRepository stockItems;
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
            throw new BusinessException("Seul un OF en brouillon peut être édité.");
        }
        // On autorise un changement de qté planifiée et de date — la
        // recette ne change pas (sinon recréer un OF, sinon on perd le
        // snapshot d'étapes).
        if (!e.recipeId.equals(payload.recipeId())) {
            throw new BusinessException("Changement de recette interdit — créez un nouvel OF.");
        }
        if (!e.siteId.equals(payload.siteId())) {
            throw new BusinessException("Changement de site interdit — créez un nouvel OF.");
        }
        RecipeEntity recipe = loadRecipe(payload.recipeId());
        BigDecimal ratio = payload.plannedQty().divide(recipe.yieldQty, 6, RoundingMode.HALF_UP);
        e.plannedQty = payload.plannedQty();
        e.scheduledDate = payload.scheduledDate();
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
            throw new BusinessException("OF déjà démarré ou clôturé (statut " + e.status + ").");
        }

        // Vérif disponibilité matières
        checkMaterialAvailability(e);

        // Consommation au CMUP courant (snapshot)
        BigDecimal totalCost = BigDecimal.ZERO;
        Instant when = Instant.now();
        for (ConsumptionLine line : e.consumptionLines) {
            // Lire le CMUP juste avant l'appel — applyMovement lui-même le
            // refigera mais on a besoin de le mémoriser sur la ligne.
            BigDecimal cmupNow = stockItems.findByArticleAndSite(line.articleId, e.siteId)
                    .map(it -> it.cmupFcfa)
                    .orElse(BigDecimal.ZERO);
            line.cmupAtConsumptionFcfa = cmupNow;
            line.totalCostFcfa = line.consumedQty
                    .multiply(cmupNow)
                    .setScale(4, RoundingMode.HALF_UP);
            totalCost = totalCost.add(line.totalCostFcfa);

            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.OUT,
                    line.consumedQty, cmupNow,
                    MovementSource.PRODUCTION, e.ref, e.id,
                    null, "Consommation OF " + e.ref,
                    null, when
            ));
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

        e.updatedAt = Instant.now();
        orders.replace(e);
        record(e, AuditEventType.MANUFACTURING_ORDER_STARTED,
                "Démarrage — " + money.format(totalCost, tenantContext.currency()) + " matières consommées");
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Advance step ──────────────────────────────────────────────

    public ProductionOrderResponseDto advanceStep(UUID id, String notes) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.IN_PROGRESS) {
            throw new BusinessException("Avance d'étape impossible (statut " + e.status + ").");
        }
        if (e.recipeStepsSnapshot.isEmpty()) {
            throw new BusinessException("Cette recette n'a pas d'étapes définies.");
        }
        if (e.currentStepIndex == null) {
            throw new BusinessException("État incohérent — aucune étape courante.");
        }
        int last = e.recipeStepsSnapshot.size() - 1;
        if (e.currentStepIndex >= last) {
            throw new BusinessException(
                    "Déjà sur la dernière étape — utilisez 'Terminer' pour clôturer l'OF.");
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
                "Étape → " + nextStep.name);
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Complete (IN_PROGRESS → COMPLETED) ────────────────────────

    public ProductionOrderResponseDto complete(UUID id, CompleteOrderDto payload) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status != OfStatus.IN_PROGRESS) {
            throw new BusinessException("OF non démarré ou déjà clôturé (statut " + e.status + ").");
        }
        BigDecimal producedQty = payload.producedQty();
        if (producedQty == null || producedQty.signum() <= 0) {
            throw new BusinessException("Quantité produite > 0 requise.");
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

        // Entrée PF en stock
        stockService.applyMovement(new MovementInput(
                e.finishedProductId, e.siteId,
                MovementKind.IN,
                producedQty, cmupPF,
                MovementSource.PRODUCTION, e.ref, e.id,
                null, "Production " + e.ref,
                null, when, false, e.lotRef
        ));

        e.completedAt = when;
        e.status = OfStatus.COMPLETED;
        e.updatedAt = when;
        orders.replace(e);

        record(e, AuditEventType.MANUFACTURING_ORDER_COMPLETED,
                "Terminé — " + producedQty + " " + e.finishedProductUnit
                        + " · CMUP " + money.format(cmupPF, tenantContext.currency()) + " · lot " + e.lotRef);
        return ProductionOrderResponseDto.from(e);
    }

    // ─── Cancel (contre-passation) ─────────────────────────────────

    public ProductionOrderResponseDto cancel(UUID id, String reason) {
        ManufacturingOrderEntity e = loadOrFail(id);
        if (e.status == OfStatus.CANCELLED) {
            throw new BusinessException("OF déjà annulé.");
        }
        OfStatus previous = e.status;
        Instant when = Instant.now();

        // DRAFT : aucun impact stock → juste basculer en CANCELLED
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

        ManufacturingOrderCancellation c = new ManufacturingOrderCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledByEmail = actor();
        c.cancelledAt = when;
        c.previousStatus = previous;
        e.cancellation = c;
        e.status = OfStatus.CANCELLED;
        e.updatedAt = when;
        orders.replace(e);

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
            throw new BusinessException("Matières insuffisantes : " + String.join(" ; ", missing));
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
            throw new BusinessException("Au moins une ligne de consommation est requise.");
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
                    () -> new BusinessException("Article " + in.articleId() + " introuvable.")
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
                    () -> new BusinessException("Article " + ing.articleId + " introuvable.")
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
                () -> new NotFoundException("OF " + id + " introuvable."));
    }

    private RecipeEntity loadRecipe(UUID id) {
        RecipeEntity r = recipes.findById(id).orElseThrow(
                () -> new NotFoundException("Recette " + id + " introuvable.")
        );
        if (!r.active) {
            throw new BusinessException("Recette « " + r.name + " » désactivée.");
        }
        return r;
    }

    private ArticleEntity loadFinishedProduct(UUID id) {
        ArticleEntity a = articles.findById(id).orElseThrow(
                () -> new NotFoundException("Produit fini " + id + " introuvable.")
        );
        if (!ArticleType.FINISHED_PRODUCT.name().equals(a.type)) {
            throw new BusinessException(
                    "L'article cible « " + a.name + " » n'est pas un produit fini.");
        }
        if (!a.active) {
            throw new BusinessException("Produit fini « " + a.name + " » désactivé.");
        }
        if (!a.stockable) {
            throw new BusinessException("Produit fini « " + a.name + " » non stockable.");
        }
        return a;
    }

    private SiteEntity loadSite(UUID id) {
        SiteEntity s = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        if (!s.active) {
            throw new BusinessException("Site « " + s.name + " » désactivé.");
        }
        return s;
    }

    private void record(ManufacturingOrderEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("manufacturing_order", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description + " — OF " + e.ref
                        + " (" + e.finishedProductName + ", "
                        + e.plannedQty + " " + e.finishedProductUnit + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
