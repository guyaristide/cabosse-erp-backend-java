package com.ntech.cabosse.achats.controller;

import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

final class PurchaseOrderExportColumns {

    private PurchaseOrderExportColumns() {}

    static List<ExportColumn<PurchaseOrderResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),     PurchaseOrderResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-status"),        b -> humanStatus(b.status() == null ? null : b.status().name())),
                ExportColumn.of(Messages.msg("m.imp-h-fournisseur"),   PurchaseOrderResponseDto::supplierName),
                ExportColumn.of(Messages.msg("m.imp-h-date-commande"), PurchaseOrderResponseDto::orderDate),
                ExportColumn.of(Messages.msg("m.imp-h-date-livraison"), PurchaseOrderResponseDto::deliveryDate),
                ExportColumn.of(Messages.msg("m.imp-h-n-facture"),    PurchaseOrderResponseDto::invoiceNumber),
                ExportColumn.of(Messages.msg("m.imp-h-date-facture"),  PurchaseOrderResponseDto::invoiceDate),
                ExportColumn.of(Messages.msg("m.imp-h-lignes"),        b -> b.lines() == null ? 0 : b.lines().size()),
                ExportColumn.of(Messages.msg("m.imp-h-sous-total-ht"), PurchaseOrderResponseDto::subtotalHt),
                ExportColumn.of(Messages.msg("m.imp-h-transport"),     PurchaseOrderResponseDto::transport),
                ExportColumn.of(Messages.msg("m.imp-h-article-vat-rate"),       PurchaseOrderResponseDto::vatRatePct),
                ExportColumn.of(Messages.msg("m.imp-h-tva-amount"),    PurchaseOrderResponseDto::vat),
                ExportColumn.of(Messages.msg("m.imp-h-total-ttc"),     PurchaseOrderResponseDto::totalTtc),
                ExportColumn.of(Messages.msg("m.imp-h-conditions"),    PurchaseOrderResponseDto::paymentTerms),
                ExportColumn.of(Messages.msg("m.imp-h-cree-par"),      PurchaseOrderResponseDto::createdByEmail),
                ExportColumn.of(Messages.msg("m.imp-h-cree-le"),       PurchaseOrderResponseDto::createdAt)
        );
    }

    private static String humanStatus(String code) {
        if (code == null) return "";
        return switch (code) {
            case "DRAFT"      -> "Brouillon";
            case "CONFIRMED"  -> "Confirmé";
            case "IN_TRANSIT" -> "En transit";
            case "DELIVERED"  -> "Livré";
            case "CANCELLED"  -> "Annulé";
            default           -> code;
        };
    }
}
