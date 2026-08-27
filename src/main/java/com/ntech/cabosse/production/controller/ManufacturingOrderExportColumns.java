package com.ntech.cabosse.production.controller;

import com.ntech.cabosse.production.dto.ProductionOrderResponseDto;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes d'export d'un ordre de fabrication (1 ligne = 1 OF). */
final class ManufacturingOrderExportColumns {

    private ManufacturingOrderExportColumns() {}

    static List<ExportColumn<ProductionOrderResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),        ProductionOrderResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date-prevue"),      ProductionOrderResponseDto::scheduledDate),
                ExportColumn.of(Messages.msg("m.imp-h-recette"),          ProductionOrderResponseDto::recipeName),
                ExportColumn.of(Messages.msg("m.imp-h-produit-fini"),     ProductionOrderResponseDto::finishedProductName),
                ExportColumn.of(Messages.msg("m.imp-h-site"),             ProductionOrderResponseDto::siteName),
                ExportColumn.of(Messages.msg("m.imp-h-qte-planifiee"),    ProductionOrderResponseDto::plannedQty),
                ExportColumn.of(Messages.msg("m.imp-h-qte-produite"),     ProductionOrderResponseDto::producedQty),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),            ProductionOrderResponseDto::finishedProductUnit),
                ExportColumn.of(Messages.msg("m.imp-h-status"),           r -> humanStatus(r.status())),
                ExportColumn.of(Messages.msg("m.imp-h-etape-courante"),   r -> currentStepName(r)),
                ExportColumn.of(Messages.msg("m.imp-h-lot"),              ProductionOrderResponseDto::lotRef),
                ExportColumn.of(Messages.msg("m.imp-h-cout-matiere"),     ProductionOrderResponseDto::totalMaterialCostFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-cmup-pf"),          ProductionOrderResponseDto::cmupAtCompletionFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-weight"), ProductionOrderResponseDto::totalWeightKg),
                ExportColumn.of(Messages.msg("m.imp-h-rendement-2"),    ProductionOrderResponseDto::completionRatePct),
                ExportColumn.of("duree-h", Messages.msg("m.imp-h-duree-h"), ColumnKind.NUMBER_QTY,        ProductionOrderResponseDto::actualDurationHours),
                ExportColumn.of(Messages.msg("m.imp-h-nb-operateurs"),    ProductionOrderResponseDto::operatorsCount),
                ExportColumn.of(Messages.msg("m.imp-h-acteur"),           ProductionOrderResponseDto::createdByEmail),
                ExportColumn.of(Messages.msg("m.imp-h-cree-le"),          ProductionOrderResponseDto::createdAt)
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
