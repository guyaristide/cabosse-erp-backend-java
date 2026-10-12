package com.ntech.cabosse.stock.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportEnumLabels;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.stock.dto.StockItemResponseDto;
import com.ntech.cabosse.stock.dto.StockMovementResponseDto;

import java.util.List;

/** Colonnes exportées pour la position stock et le journal des mouvements. */
final class StockExportColumns {

    private StockExportColumns() {}

    /** Colonnes pour la situation stock (1 ligne = 1 couple article × site). */
    static List<ExportColumn<StockItemResponseDto>> stocks() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-article"),       StockItemResponseDto::articleName),
                ExportColumn.of(Messages.msg("m.imp-h-code"),          StockItemResponseDto::articleCode),
                ExportColumn.of(Messages.msg("m.imp-h-expense-type-category"),     r -> humanType(r.articleType() == null ? null : r.articleType().name())),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),         StockItemResponseDto::articleUnit),
                ExportColumn.of(Messages.msg("m.imp-h-receipt-quantity"),      StockItemResponseDto::quantity),
                ExportColumn.of(Messages.msg("m.imp-h-cmup-amount"),   StockItemResponseDto::cmup),
                ExportColumn.of(Messages.msg("m.imp-h-valeur-totale"), StockItemResponseDto::totalValue),
                ExportColumn.of(Messages.msg("m.imp-h-seuil-d-alerte"), StockItemResponseDto::alertThreshold),
                ExportColumn.of(Messages.msg("m.imp-h-dernier-mvt"),   StockItemResponseDto::lastMovementAt)
        );
    }

    /** Colonnes pour le journal des mouvements (1 ligne = 1 mouvement). */
    static List<ExportColumn<StockMovementResponseDto>> movements() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),      StockMovementResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date"),           StockMovementResponseDto::occurredAt),
                ExportColumn.of(Messages.msg("m.imp-h-type"),
                        r -> ExportEnumLabels.movementKind(r.kind() == null ? null : r.kind().name())),
                ExportColumn.of(Messages.msg("m.imp-h-article"),        StockMovementResponseDto::articleName),
                ExportColumn.of(Messages.msg("m.imp-h-code"),           StockMovementResponseDto::articleCode),
                ExportColumn.of(Messages.msg("m.imp-h-site"),           StockMovementResponseDto::siteName),
                ExportColumn.of(Messages.msg("m.imp-h-receipt-quantity"),       StockMovementResponseDto::quantitySigned),
                // Les sacs de l'amorçage : seul mouvement à en porter, et
                // ils n'étaient relisibles nulle part une fois saisis.
                ExportColumn.of(Messages.msg("m.imp-h-nb-sacs"),        StockMovementResponseDto::bags),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),          StockMovementResponseDto::articleUnit),
                ExportColumn.of(Messages.msg("m.imp-h-pu-amount"),      StockMovementResponseDto::unitPrice),
                ExportColumn.of(Messages.msg("m.imp-h-total-amount"),   StockMovementResponseDto::total),
                ExportColumn.of(Messages.msg("m.imp-h-cmup-apres"),     StockMovementResponseDto::cmupAfter),
                ExportColumn.of(Messages.msg("m.imp-h-qte-apres"),      StockMovementResponseDto::quantityAfter),
                ExportColumn.of(Messages.msg("m.imp-h-origine"),
                        r -> ExportEnumLabels.movementSource(
                                r.sourceType() == null ? null : r.sourceType().name())),
                ExportColumn.of(Messages.msg("m.imp-h-ref-origine"),   StockMovementResponseDto::sourceRef),
                ExportColumn.of(Messages.msg("m.imp-h-motif"),          StockMovementResponseDto::reason),
                ExportColumn.of(Messages.msg("m.imp-h-acteur"),         StockMovementResponseDto::actorEmail)
        );
    }

    /**
     * Nature de l'article. Deux natures y manquaient, marchandise et
     * transport, et un article de ces natures sortait avec son code
     * technique (12/10/2026).
     */
    private static String humanType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "RAW_MATERIAL"      -> Messages.msg("m.artt-raw-material");
            case "MERCHANDISE"       -> Messages.msg("m.artt-merchandise");
            case "CONSUMABLE"        -> Messages.msg("m.artt-consumable");
            case "PACKAGING"         -> Messages.msg("m.artt-packaging");
            case "FINISHED_PRODUCT"  -> Messages.msg("m.artt-finished-product");
            case "TRANSPORT"         -> Messages.msg("m.artt-transport");
            default                  -> code;
        };
    }

}
