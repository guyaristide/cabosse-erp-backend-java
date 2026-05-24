package com.ntech.cabosse.reception.controller;

import com.ntech.cabosse.reception.dto.DirectReceiptResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/**
 * Colonnes exportées d'une session de réception directe — 1 ligne
 * d'export = 1 session (pas une ligne de la session). Pour un export
 * "à plat" par ligne producteur, faudrait un endpoint distinct (à
 * envisager si demande utilisateur).
 */
final class DirectReceiptExportColumns {

    private DirectReceiptExportColumns() {}

    static List<ExportColumn<DirectReceiptResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",     DirectReceiptResponseDto::ref),
                ExportColumn.of("Date réception", DirectReceiptResponseDto::receivedDate),
                ExportColumn.of("Article",       DirectReceiptResponseDto::articleName),
                ExportColumn.of("Code article",  DirectReceiptResponseDto::articleCode),
                ExportColumn.of("Unité",         DirectReceiptResponseDto::articleUnit),
                ExportColumn.of("Producteurs",   r -> r.lines() == null ? 0 : r.lines().size()),
                ExportColumn.of("Statut",        r -> humanStatus(r.status() == null ? null : r.status().name())),
                ExportColumn.of("Total HT",      DirectReceiptResponseDto::subtotalHtFcfa),
                ExportColumn.of("Total payé",    DirectReceiptResponseDto::totalPaidFcfa),
                ExportColumn.of("N° BL session", DirectReceiptResponseDto::deliveryNoteRef),
                ExportColumn.of("Réceptionné par", DirectReceiptResponseDto::receiverEmail),
                ExportColumn.of("Créé le",       DirectReceiptResponseDto::createdAt)
        );
    }

    private static String humanStatus(String code) {
        if (code == null) return "";
        return switch (code) {
            case "UNPAID"    -> "Dû";
            case "PARTIAL"   -> "Partiel";
            case "PAID"      -> "Payé";
            case "CANCELLED" -> "Annulé";
            default          -> code;
        };
    }
}
