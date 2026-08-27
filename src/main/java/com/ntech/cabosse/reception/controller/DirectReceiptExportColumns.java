package com.ntech.cabosse.reception.controller;

import com.ntech.cabosse.reception.dto.DirectReceiptResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

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
                ExportColumn.of(Messages.msg("m.imp-h-reference"),     DirectReceiptResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date-reception"), DirectReceiptResponseDto::receivedDate),
                ExportColumn.of(Messages.msg("m.imp-h-article"),       DirectReceiptResponseDto::articleName),
                ExportColumn.of(Messages.msg("m.imp-h-code-article"),  DirectReceiptResponseDto::articleCode),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),         DirectReceiptResponseDto::articleUnit),
                ExportColumn.of(Messages.msg("m.imp-h-producteurs"),   r -> r.lines() == null ? 0 : r.lines().size()),
                ExportColumn.of(Messages.msg("m.imp-h-status"),        r -> humanStatus(r.status() == null ? null : r.status().name())),
                ExportColumn.of(Messages.msg("m.imp-h-total-ht"),      DirectReceiptResponseDto::subtotalHtFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-total-paye"),    DirectReceiptResponseDto::totalPaidFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-n-bl-session"), DirectReceiptResponseDto::deliveryNoteRef),
                ExportColumn.of(Messages.msg("m.imp-h-receptionne-par"), DirectReceiptResponseDto::receiverEmail),
                ExportColumn.of(Messages.msg("m.imp-h-cree-le"),       DirectReceiptResponseDto::createdAt)
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
