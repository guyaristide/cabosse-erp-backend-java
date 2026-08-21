package com.ntech.cabosse.expense.controller;

import com.ntech.cabosse.expense.dto.DirectExpenseResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des dépenses directes. */
final class DirectExpenseExportColumns {

    private DirectExpenseExportColumns() {}

    static List<ExportColumn<DirectExpenseResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",          DirectExpenseResponseDto::ref),
                ExportColumn.of("Type",               DirectExpenseResponseDto::kind),
                ExportColumn.of("Date",               DirectExpenseResponseDto::expenseDate),
                ExportColumn.of("Fournisseur",        DirectExpenseResponseDto::supplierName),
                ExportColumn.of("Nature",             DirectExpenseResponseDto::expenseTypeName),
                ExportColumn.of("Compte de charge",   DirectExpenseResponseDto::chargeAccount),
                ExportColumn.of("Libellé",            DirectExpenseResponseDto::label),
                ExportColumn.of("Période",            DirectExpenseResponseDto::periodLabel),
                ExportColumn.of("Clé de répartition", DirectExpenseResponseDto::allocationKeyName),
                ExportColumn.of("Montant HT (FCFA)",  DirectExpenseResponseDto::amountHtFcfa),
                ExportColumn.of("TVA (FCFA)",         DirectExpenseResponseDto::vatAmountFcfa),
                ExportColumn.of("Montant TTC (FCFA)", DirectExpenseResponseDto::amountTtcFcfa),
                ExportColumn.of("Mode de paiement",   DirectExpenseResponseDto::paymentMethod),
                ExportColumn.of("Pièce comptable",    DirectExpenseResponseDto::pieceRef));
    }
}
