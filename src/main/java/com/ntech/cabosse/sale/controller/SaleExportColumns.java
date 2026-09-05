package com.ntech.cabosse.sale.controller;

import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes exportées d'une vente. */
final class SaleExportColumns {

    private SaleExportColumns() {}

    static List<ExportColumn<SaleResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),         SaleResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date-vente"),        SaleResponseDto::saleDate),
                ExportColumn.of(Messages.msg("m.imp-h-echeance"),          SaleResponseDto::dueDate),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-customer"),            SaleResponseDto::customerName),
                ExportColumn.of(Messages.msg("m.imp-h-site"),              SaleResponseDto::siteName),
                ExportColumn.of(Messages.msg("m.imp-h-canal"),             r -> humanChannel(r.channel())),
                ExportColumn.of(Messages.msg("m.imp-h-status"),            r -> humanStatus(r.status())),
                ExportColumn.of(Messages.msg("m.imp-h-paiement"),          r -> humanPayment(r.paymentStatus())),
                ExportColumn.of(Messages.msg("m.imp-h-total-ht"),          SaleResponseDto::subtotalHt),
                ExportColumn.of(Messages.msg("m.imp-h-remise"),            SaleResponseDto::discount),
                ExportColumn.of(Messages.msg("m.imp-h-tva"),               SaleResponseDto::vat),
                ExportColumn.of(Messages.msg("m.imp-h-total-ttc"),         SaleResponseDto::totalTtc),
                ExportColumn.of(Messages.msg("m.imp-h-cout-matiere"),      SaleResponseDto::totalCost),
                ExportColumn.of(Messages.msg("m.imp-h-marge-brute"),       SaleResponseDto::grossMargin),
                ExportColumn.of(Messages.msg("m.imp-h-total-paye"),        SaleResponseDto::totalPaid),
                ExportColumn.of(Messages.msg("m.imp-h-solde-facture"),     SaleResponseDto::balanceDue),
                ExportColumn.of(Messages.msg("m.imp-h-etat-facture"),      r -> invoiceState(r.paymentStatus())),
                ExportColumn.of(Messages.msg("m.imp-h-n-facture"),        SaleResponseDto::invoiceNumber),
                ExportColumn.of(Messages.msg("m.imp-h-cree-par"),          SaleResponseDto::createdByEmail),
                ExportColumn.of(Messages.msg("m.imp-h-cree-le"),           SaleResponseDto::createdAt)
        );
    }

    /**
     * État facture côté client : "Soldée" si entièrement payée,
     * "Partielle" si en partie réglée, "Impayée" sinon. Libellés
     * alignés sur le template Excel attendu par l'expert métier — à
     * garder cohérents avec {@link #humanPayment(PaymentStatus)}.
     */
    private static String invoiceState(PaymentStatus p) {
        if (p == null) return "Impayée";
        return switch (p) {
            case PAID    -> "Soldée";
            case PARTIAL -> "Partielle";
            case UNPAID  -> "Impayée";
        };
    }

    private static String humanChannel(SaleChannel c) {
        return c == null ? "" : c.name();
    }

    private static String humanStatus(SaleStatus s) {
        if (s == null) return "";
        return switch (s) {
            case QUOTE     -> "Devis";
            case CONFIRMED -> "Confirmée";
            case DELIVERED -> "Finalisée";
            case CANCELLED -> "Annulée";
        };
    }

    private static String humanPayment(PaymentStatus p) {
        if (p == null) return "";
        return switch (p) {
            case UNPAID  -> "Impayée";
            case PARTIAL -> "Partielle";
            case PAID    -> "Soldée";
        };
    }
}
