package com.ntech.cabosse.expense.controller;

import com.ntech.cabosse.expense.dto.DirectExpenseResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des dépenses directes. */
final class DirectExpenseExportColumns {

    private DirectExpenseExportColumns() {}

    static List<ExportColumn<DirectExpenseResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),          DirectExpenseResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-type"),               DirectExpenseResponseDto::kind),
                ExportColumn.of(Messages.msg("m.imp-h-date"),               DirectExpenseResponseDto::expenseDate),
                ExportColumn.of(Messages.msg("m.imp-h-fournisseur"),        DirectExpenseResponseDto::supplierName),
                ExportColumn.of(Messages.msg("m.imp-h-member-person-type"),             DirectExpenseResponseDto::expenseTypeName),
                ExportColumn.of(Messages.msg("m.imp-h-compte-de-charge"),   DirectExpenseResponseDto::chargeAccount),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),            DirectExpenseResponseDto::label),
                ExportColumn.of(Messages.msg("m.imp-h-periode"),            DirectExpenseResponseDto::periodLabel),
                ExportColumn.of(Messages.msg("m.imp-h-cle-de-repartition"), DirectExpenseResponseDto::allocationKeyName),
                ExportColumn.of(Messages.msg("m.imp-h-montant-ht-fcfa"),  DirectExpenseResponseDto::amountHtFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-tva-fcfa"),         DirectExpenseResponseDto::vatAmountFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-montant-ttc-fcfa"), DirectExpenseResponseDto::amountTtcFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-method"),   DirectExpenseResponseDto::paymentMethod),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"),    DirectExpenseResponseDto::pieceRef));
    }
}
