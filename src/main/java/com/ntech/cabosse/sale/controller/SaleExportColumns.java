package com.ntech.cabosse.sale.controller;

import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes exportées d'une vente. */
final class SaleExportColumns {

    private SaleExportColumns() {}

    static List<ExportColumn<SaleResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",         SaleResponseDto::ref),
                ExportColumn.of("Date vente",        SaleResponseDto::saleDate),
                ExportColumn.of("Échéance",          SaleResponseDto::dueDate),
                ExportColumn.of("Client",            SaleResponseDto::customerName),
                ExportColumn.of("Site",              SaleResponseDto::siteName),
                ExportColumn.of("Canal",             r -> humanChannel(r.channel())),
                ExportColumn.of("Statut",            r -> humanStatus(r.status())),
                ExportColumn.of("Paiement",          r -> humanPayment(r.paymentStatus())),
                ExportColumn.of("Total HT",          SaleResponseDto::subtotalHtFcfa),
                ExportColumn.of("Remise",            SaleResponseDto::discountFcfa),
                ExportColumn.of("TVA",               SaleResponseDto::vatFcfa),
                ExportColumn.of("Total TTC",         SaleResponseDto::totalTtcFcfa),
                ExportColumn.of("Coût matière",      SaleResponseDto::totalCostFcfa),
                ExportColumn.of("Marge brute",       SaleResponseDto::grossMarginFcfa),
                ExportColumn.of("Total payé",        SaleResponseDto::totalPaidFcfa),
                ExportColumn.of("Solde facture",     SaleResponseDto::balanceDueFcfa),
                ExportColumn.of("Etat facture",      r -> invoiceState(r.paymentStatus())),
                ExportColumn.of("N° facture",        SaleResponseDto::invoiceNumber),
                ExportColumn.of("Créé par",          SaleResponseDto::createdByEmail),
                ExportColumn.of("Créé le",           SaleResponseDto::createdAt)
        );
    }

    /**
     * État facture côté client : "Soldée" si entièrement payée, sinon
     * "Non soldée" (couvre les statuts UNPAID et PARTIAL). Aligné sur
     * le format attendu dans le template Excel client.
     */
    private static String invoiceState(PaymentStatus p) {
        return p == PaymentStatus.PAID ? "Soldée" : "Non soldée";
    }

    private static String humanChannel(SaleChannel c) {
        return c == null ? "" : c.name();
    }

    private static String humanStatus(SaleStatus s) {
        if (s == null) return "";
        return switch (s) {
            case QUOTE     -> "Devis";
            case CONFIRMED -> "Confirmée";
            case DELIVERED -> "Livrée";
            case CANCELLED -> "Annulée";
        };
    }

    private static String humanPayment(PaymentStatus p) {
        if (p == null) return "";
        return switch (p) {
            case UNPAID  -> "Dû";
            case PARTIAL -> "Partiel";
            case PAID    -> "Payé";
        };
    }
}
