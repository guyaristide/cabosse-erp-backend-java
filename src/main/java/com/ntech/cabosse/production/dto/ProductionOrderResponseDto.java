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
        BigDecimal totalMaterialCostFcfa,
        BigDecimal cmupAtCompletionFcfa,

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
            BigDecimal cmupAtConsumptionFcfa, BigDecimal totalCostFcfa
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
                e.plannedQty, e.producedQty, e.lotRef, e.scheduledDate, e.startedAt, e.completedAt,
                e.currentStepIndex,
                e.stepHistory == null
                        ? List.of()
                        : e.stepHistory.stream().map(ProductionOrderResponseDto::progressView).toList(),
                e.consumptionLines == null
                        ? List.of()
                        : e.consumptionLines.stream().map(ProductionOrderResponseDto::lineView).toList(),
                e.totalMaterialCostFcfa, e.cmupAtCompletionFcfa,
                e.notes,
                e.cancellation == null ? null : new CancellationView(
                        e.cancellation.reason, e.cancellation.cancelledByEmail,
                        e.cancellation.cancelledAt, e.cancellation.previousStatus
                ),
                e.createdAt, e.updatedAt, e.createdByEmail
        );
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
                l.plannedQty, l.consumedQty, l.cmupAtConsumptionFcfa, l.totalCostFcfa
        );
    }
}
