package com.ntech.cabosse.stock.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.stock.dto.StockItemResponseDto;
import com.ntech.cabosse.stock.dto.StockMovementResponseDto;
import com.ntech.cabosse.stock.entity.MovementKind;

import java.util.List;

/** Colonnes exportées pour la position stock et le journal des mouvements. */
final class StockExportColumns {

    private StockExportColumns() {}

    /** Colonnes pour la situation stock (1 ligne = 1 couple article × site). */
    static List<ExportColumn<StockItemResponseDto>> stocks() {
        return List.of(
                ExportColumn.of("Article",       StockItemResponseDto::articleName),
                ExportColumn.of("Code",          StockItemResponseDto::articleCode),
                ExportColumn.of("Catégorie",     r -> humanType(r.articleType() == null ? null : r.articleType().name())),
                ExportColumn.of("Unité",         StockItemResponseDto::articleUnit),
                ExportColumn.of("Quantité",      StockItemResponseDto::quantity),
                ExportColumn.of("CMUP (FCFA)",   StockItemResponseDto::cmupFcfa),
                ExportColumn.of("Valeur totale", StockItemResponseDto::totalValueFcfa),
                ExportColumn.of("Seuil d'alerte", StockItemResponseDto::alertThreshold),
                ExportColumn.of("Dernier mvt",   StockItemResponseDto::lastMovementAt)
        );
    }

    /** Colonnes pour le journal des mouvements (1 ligne = 1 mouvement). */
    static List<ExportColumn<StockMovementResponseDto>> movements() {
        return List.of(
                ExportColumn.of("Référence",      StockMovementResponseDto::ref),
                ExportColumn.of("Date",           StockMovementResponseDto::occurredAt),
                ExportColumn.of("Type",           r -> humanKind(r.kind())),
                ExportColumn.of("Article",        StockMovementResponseDto::articleName),
                ExportColumn.of("Code",           StockMovementResponseDto::articleCode),
                ExportColumn.of("Site",           StockMovementResponseDto::siteName),
                ExportColumn.of("Quantité",       StockMovementResponseDto::quantitySigned),
                ExportColumn.of("Unité",          StockMovementResponseDto::articleUnit),
                ExportColumn.of("PU (FCFA)",      StockMovementResponseDto::unitPriceFcfa),
                ExportColumn.of("Total (FCFA)",   StockMovementResponseDto::totalFcfa),
                ExportColumn.of("CMUP après",     StockMovementResponseDto::cmupAfterFcfa),
                ExportColumn.of("Qté après",      StockMovementResponseDto::quantityAfter),
                ExportColumn.of("Origine",        r -> r.sourceType() == null ? "" : r.sourceType().name()),
                ExportColumn.of("Réf. origine",   StockMovementResponseDto::sourceRef),
                ExportColumn.of("Motif",          StockMovementResponseDto::reason),
                ExportColumn.of("Acteur",         StockMovementResponseDto::actorEmail)
        );
    }

    private static String humanType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "RAW_MATERIAL"      -> "Matière première";
            case "CONSUMABLE"        -> "Consommable";
            case "PACKAGING"         -> "Emballage";
            case "FINISHED_PRODUCT"  -> "Produit fini";
            default                  -> code;
        };
    }

    private static String humanKind(MovementKind k) {
        if (k == null) return "";
        return switch (k) {
            case IN            -> "Entrée";
            case OUT           -> "Sortie";
            case ADJUSTMENT    -> "Ajustement";
            case OPENING       -> "Amorçage";
            case TRANSFER_OUT  -> "Transfert sortant";
            case TRANSFER_IN   -> "Transfert entrant";
        };
    }
}
