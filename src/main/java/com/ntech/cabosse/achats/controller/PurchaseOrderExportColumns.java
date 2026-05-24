package com.ntech.cabosse.achats.controller;

import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

final class PurchaseOrderExportColumns {

    private PurchaseOrderExportColumns() {}

    static List<ExportColumn<PurchaseOrderResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",     PurchaseOrderResponseDto::ref),
                ExportColumn.of("Statut",        b -> humanStatus(b.status() == null ? null : b.status().name())),
                ExportColumn.of("Fournisseur",   PurchaseOrderResponseDto::supplierName),
                ExportColumn.of("Date commande", PurchaseOrderResponseDto::orderDate),
                ExportColumn.of("Date livraison", PurchaseOrderResponseDto::deliveryDate),
                ExportColumn.of("N° facture",    PurchaseOrderResponseDto::invoiceNumber),
                ExportColumn.of("Date facture",  PurchaseOrderResponseDto::invoiceDate),
                ExportColumn.of("Lignes",        b -> b.lines() == null ? 0 : b.lines().size()),
                ExportColumn.of("Sous-total HT", PurchaseOrderResponseDto::subtotalHtFcfa),
                ExportColumn.of("Transport",     PurchaseOrderResponseDto::transportFcfa),
                ExportColumn.of("TVA (%)",       PurchaseOrderResponseDto::vatRatePct),
                ExportColumn.of("TVA (FCFA)",    PurchaseOrderResponseDto::vatFcfa),
                ExportColumn.of("Total TTC",     PurchaseOrderResponseDto::totalTtcFcfa),
                ExportColumn.of("Conditions",    PurchaseOrderResponseDto::paymentTerms),
                ExportColumn.of("Créé par",      PurchaseOrderResponseDto::createdByEmail),
                ExportColumn.of("Créé le",       PurchaseOrderResponseDto::createdAt)
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
