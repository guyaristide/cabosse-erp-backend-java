package com.ntech.cabosse.production.controller;

import com.ntech.cabosse.production.dto.ProductionOrderResponseDto;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes d'export d'un ordre de fabrication (1 ligne = 1 OF). */
final class ManufacturingOrderExportColumns {

    private ManufacturingOrderExportColumns() {}

    static List<ExportColumn<ProductionOrderResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",        ProductionOrderResponseDto::ref),
                ExportColumn.of("Date prévue",      ProductionOrderResponseDto::scheduledDate),
                ExportColumn.of("Recette",          ProductionOrderResponseDto::recipeName),
                ExportColumn.of("Produit fini",     ProductionOrderResponseDto::finishedProductName),
                ExportColumn.of("Site",             ProductionOrderResponseDto::siteName),
                ExportColumn.of("Qté planifiée",    ProductionOrderResponseDto::plannedQty),
                ExportColumn.of("Qté produite",     ProductionOrderResponseDto::producedQty),
                ExportColumn.of("Unité",            ProductionOrderResponseDto::finishedProductUnit),
                ExportColumn.of("Statut",           r -> humanStatus(r.status())),
                ExportColumn.of("Étape courante",   r -> currentStepName(r)),
                ExportColumn.of("Lot",              ProductionOrderResponseDto::lotRef),
                ExportColumn.of("Coût matière",     ProductionOrderResponseDto::totalMaterialCostFcfa),
                ExportColumn.of("CMUP PF",          ProductionOrderResponseDto::cmupAtCompletionFcfa),
                ExportColumn.of("Poids total (kg)", ProductionOrderResponseDto::totalWeightKg),
                ExportColumn.of("Rendement (%)",    ProductionOrderResponseDto::completionRatePct),
                ExportColumn.of("duree-h", "Durée (h)", ColumnKind.NUMBER_QTY,        ProductionOrderResponseDto::actualDurationHours),
                ExportColumn.of("Nb opérateurs",    ProductionOrderResponseDto::operatorsCount),
                ExportColumn.of("Acteur",           ProductionOrderResponseDto::createdByEmail),
                ExportColumn.of("Créé le",          ProductionOrderResponseDto::createdAt)
        );
    }

    private static String humanStatus(OfStatus s) {
        if (s == null) return "";
        return switch (s) {
            case DRAFT       -> "Brouillon";
            case IN_PROGRESS -> "En cours";
            case COMPLETED   -> "Terminé";
            case CANCELLED   -> "Annulé";
        };
    }

    private static String currentStepName(ProductionOrderResponseDto r) {
        if (r.currentStepIndex() == null) return "";
        if (r.recipeStepsSnapshot() == null || r.recipeStepsSnapshot().isEmpty()) return "";
        int idx = r.currentStepIndex();
        if (idx < 0 || idx >= r.recipeStepsSnapshot().size()) return "";
        return r.recipeStepsSnapshot().get(idx).name();
    }
}
