package com.ntech.cabosse.production.dto;

import com.ntech.cabosse.production.entity.ConsumptionLine;
import com.ntech.cabosse.production.entity.ManufacturingOrderCancellation;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.production.entity.StepProgress;
import com.ntech.cabosse.recipe.entity.RecipeStep;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Vue lecture d'un ordre de fabrication, incluant lignes et progression. */
@Schema(description = "Ordre de fabrication")
public record ProductionOrderResponseDto(
        UUID id,
        String ref,
        UUID siteId,
        String siteName,
        OfStatus status,

        // Recette + snapshot étapes
        UUID recipeId,
        String recipeCode,
        String recipeName,
        BigDecimal recipeYieldQty,
        String recipeYieldUnit,
        List<RecipeStepView> recipeStepsSnapshot,

        // PF cible
        UUID finishedProductId,
        String finishedProductCode,
        String finishedProductName,
        String finishedProductUnit,
        /** Poids unitaire du PF en grammes — snapshoté à la création. */
        Integer finishedProductUnitWeightGrams,

        // Planification
        BigDecimal plannedQty,
        BigDecimal producedQty,
        String lotRef,
        LocalDate scheduledDate,
        Instant startedAt,
        Instant completedAt,

        // Suivi étapes
        Integer currentStepIndex,
        List<StepProgressView> stepHistory,

        // Coûts
        List<ConsumptionLineView> consumptionLines,
        BigDecimal totalMaterialCost,
        BigDecimal cmupAtCompletion,

        // KPIs production (saisis ou dérivés à la complétion)
        Integer actualDurationHours,
        Integer operatorsCount,
        /** Poids total produit en kg ({@code null} si pas de poids unitaire ou pas produit). */
        BigDecimal totalWeightKg,
        /** Taux de réalisation = producedQty / plannedQty × 100 ({@code null} avant complétion). */
        BigDecimal completionRatePct,
        /**
         * Poids total matière consommée en kg — somme des
         * {@code consumptionLines.plannedQty} dont l'unité est {@code kg}.
         * Les lignes en unités/cartons/sachets sont ignorées (pas de
         * conversion universelle vers kg). {@code null} si aucune ligne
         * en kg.
         */
        BigDecimal totalMaterialWeightKg,
        /**
         * Rendement matière en pourcentage = {@code totalWeightKg /
         * totalMaterialWeightKg × 100}. C'est le KPI au sens "fichier
         * client" (sortie / entrée en masse). {@code null} si l'un des
         * deux poids n'est pas calculable.
         */
        BigDecimal materialYieldPct,

        String notes,
        CancellationView cancellation,

        Instant createdAt,
        Instant updatedAt,
        String createdByEmail
) {

    public record RecipeStepView(int order, String name, String description, Integer expectedDurationMinutes) {}
    public record StepProgressView(int stepOrder, String stepName, Instant startedAt, Instant completedAt, String notes) {}
    public record ConsumptionLineView(
            UUID id, UUID articleId, String articleCode, String articleName, String articleUnit,
            BigDecimal plannedQty, BigDecimal consumedQty,
            BigDecimal cmupAtConsumption, BigDecimal totalCost
    ) {}
    public record CancellationView(String reason, String cancelledByEmail, Instant cancelledAt, OfStatus previousStatus) {}

    public static ProductionOrderResponseDto from(ManufacturingOrderEntity e) {
        return new ProductionOrderResponseDto(
                e.id, e.ref, e.siteId, e.siteName, e.status,
                e.recipeId, e.recipeCode, e.recipeName, e.recipeYieldQty, e.recipeYieldUnit,
                e.recipeStepsSnapshot == null
                        ? List.of()
                        : e.recipeStepsSnapshot.stream().map(ProductionOrderResponseDto::stepView).toList(),
                e.finishedProductId, e.finishedProductCode, e.finishedProductName, e.finishedProductUnit,
                e.finishedProductUnitWeightGrams,
                e.plannedQty, e.producedQty, e.lotRef, e.scheduledDate, e.startedAt, e.completedAt,
                e.currentStepIndex,
                e.stepHistory == null
                        ? List.of()
                        : e.stepHistory.stream().map(ProductionOrderResponseDto::progressView).toList(),
                e.consumptionLines == null
                        ? List.of()
                        : e.consumptionLines.stream().map(ProductionOrderResponseDto::lineView).toList(),
                e.totalMaterialCost, e.cmupAtCompletion,
                e.actualDurationHours, e.operatorsCount,
                totalWeightKg(e), completionRatePct(e),
                totalMaterialWeightKg(e), materialYieldPct(e),
                e.notes,
                e.cancellation == null ? null : new CancellationView(
                        e.cancellation.reason, e.cancellation.cancelledByEmail,
                        e.cancellation.cancelledAt, e.cancellation.previousStatus
                ),
                e.createdAt, e.updatedAt, e.createdByEmail
        );
    }

    /**
     * Poids total produit en kg = {@code producedQty × unitWeightGrams / 1000}.
     * {@code null} si l'un des deux est absent. Pertinent surtout pour les
     * PF unitaires (tablettes, boîtes, sachets) — en kg/L l'unité est
     * déjà la masse.
     */
    private static BigDecimal totalWeightKg(ManufacturingOrderEntity e) {
        if (e.producedQty == null || e.finishedProductUnitWeightGrams == null) return null;
        return e.producedQty
                .multiply(BigDecimal.valueOf(e.finishedProductUnitWeightGrams))
                .divide(BigDecimal.valueOf(1000), 3, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Somme des {@code plannedQty} des lignes de consommation dont
     * l'unité est {@code kg} (case-insensitive). Les autres unités
     * (unité, sachet, carton, planche, rouleau, forfait…) n'ont pas
     * de conversion universelle vers kg et sont donc exclues du calcul
     * — c'est intentionnel, sinon le rendement matière serait faussé.
     * Retourne {@code null} si aucune ligne en kg.
     */
    private static BigDecimal totalMaterialWeightKg(ManufacturingOrderEntity e) {
        if (e.consumptionLines == null || e.consumptionLines.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean hasAny = false;
        for (var line : e.consumptionLines) {
            if (line.plannedQty == null) continue;
            String unit = line.articleUnit == null ? "" : line.articleUnit.trim().toLowerCase();
            if ("kg".equals(unit)) {
                total = total.add(line.plannedQty);
                hasAny = true;
            }
        }
        return hasAny ? total : null;
    }

    /**
     * Rendement matière en pourcentage = {@code totalWeightKg /
     * totalMaterialWeightKg × 100}. Aligné sur le KPI du fichier client
     * (sortie produite vs entrée matière, en masse). {@code null} si
     * l'un des deux poids manque.
     */
    private static BigDecimal materialYieldPct(ManufacturingOrderEntity e) {
        BigDecimal output = totalWeightKg(e);
        BigDecimal input = totalMaterialWeightKg(e);
        if (output == null || input == null || input.signum() == 0) return null;
        return output
                .multiply(BigDecimal.valueOf(100))
                .divide(input, 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Taux de réalisation en pourcentage = {@code producedQty / plannedQty × 100}.
     * Mesure le rendement quantitatif basique de l'OF — différent du
     * rendement matière (qui compare poids produit vs poids consommé,
     * laissé au calcul client pour l'instant).
     */
    private static BigDecimal completionRatePct(ManufacturingOrderEntity e) {
        if (e.producedQty == null || e.plannedQty == null || e.plannedQty.signum() == 0) return null;
        return e.producedQty
                .multiply(BigDecimal.valueOf(100))
                .divide(e.plannedQty, 2, java.math.RoundingMode.HALF_UP);
    }

    private static RecipeStepView stepView(RecipeStep s) {
        return new RecipeStepView(s.order, s.name, s.description, s.expectedDurationMinutes);
    }

    private static StepProgressView progressView(StepProgress p) {
        return new StepProgressView(p.stepOrder, p.stepName, p.startedAt, p.completedAt, p.notes);
    }

    private static ConsumptionLineView lineView(ConsumptionLine l) {
        return new ConsumptionLineView(
                l.id, l.articleId, l.articleCode, l.articleName, l.articleUnit,
                l.plannedQty, l.consumedQty, l.cmupAtConsumption, l.totalCost
        );
    }
}
