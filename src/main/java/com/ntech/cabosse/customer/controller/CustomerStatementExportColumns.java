package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.customer.dto.CustomerStatementDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Les colonnes du fil des opérations d'un client : date, nature, référence, libellé, kilos, montant, campagne. */
final class CustomerStatementExportColumns {
    private CustomerStatementExportColumns() {}

    static List<ExportColumn<CustomerStatementDto.Operation>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"),            CustomerStatementDto.Operation::date),
                ExportColumn.of(Messages.msg("m.imp-h-nature"), op -> operationKind(op.type())),
                ExportColumn.of(Messages.msg("m.imp-h-reference"),       CustomerStatementDto.Operation::ref),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),         CustomerStatementDto.Operation::label),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),        CustomerStatementDto.Operation::quantityKg),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),  CustomerStatementDto.Operation::amount),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), CustomerStatementDto.Operation::campaignLabel));
    }

    private static String operationKind(String code) {
        if (code == null) return null;
        return switch (code) {
            case "SALE" -> Messages.msg("m.cusop-sale");
            case "COMMODITY_SALE" -> Messages.msg("m.cusop-commodity-sale");
            case "PAYMENT" -> Messages.msg("m.cusop-payment");
            case "CONTRACT" -> Messages.msg("m.cusop-contract");
            default -> code;
        };
    }
}
