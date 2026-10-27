package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.dto.SupplierStatementDto;

import java.util.List;

/** Les colonnes du fil des opérations d'un fournisseur : date, nature, référence, libellé, kilos, montant, campagne. */
final class SupplierStatementExportColumns {
    private SupplierStatementExportColumns() {}

    static List<ExportColumn<SupplierStatementDto.Operation>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"),            SupplierStatementDto.Operation::date),
                ExportColumn.of(Messages.msg("m.imp-h-nature"), op -> operationKind(op.type())),
                ExportColumn.of(Messages.msg("m.imp-h-reference"),       SupplierStatementDto.Operation::ref),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),         SupplierStatementDto.Operation::label),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),        SupplierStatementDto.Operation::quantityKg),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),  SupplierStatementDto.Operation::amount),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), SupplierStatementDto.Operation::campaignLabel));
    }

    private static String operationKind(String code) {
        if (code == null) return null;
        return switch (code) {
            case "DELIVERY" -> Messages.msg("m.supop-delivery");
            case "PAYMENT" -> Messages.msg("m.supop-payment");
            case "ADVANCE" -> Messages.msg("m.supop-advance");
            case "REFUND" -> Messages.msg("m.supop-refund");
            case "PURCHASE_ORDER" -> Messages.msg("m.supop-purchase-order");
            case "RECEPTION" -> Messages.msg("m.supop-reception");
            case "EXPENSE" -> Messages.msg("m.supop-expense");
            default -> code;
        };
    }
}
