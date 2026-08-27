package com.ntech.cabosse.expensetype.controller;

import com.ntech.cabosse.expensetype.dto.ExpenseTypeResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

final class ExpenseTypeExportColumns {

    private ExpenseTypeExportColumns() {}

    static List<ExportColumn<ExpenseTypeResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),             ExpenseTypeResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),              ExpenseTypeResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-expense-type-category"),        e -> humanCategory(e.category())),
                ExportColumn.of(Messages.msg("m.imp-h-expense-type-account"), ExpenseTypeResponseDto::syscohadaAccount),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),            ExpenseTypeResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-description"),      ExpenseTypeResponseDto::description)
        );
    }

    private static String humanCategory(String code) {
        if (code == null) return "";
        return switch (code) {
            case "LOGISTICS" -> "Logistique";
            case "UTILITIES" -> "Services (eau, électricité, télécom)";
            case "ADMIN"     -> "Administratif";
            case "MARKETING" -> "Marketing & commercial";
            case "PERSONNEL" -> "Personnel";
            case "FINANCIAL" -> "Financier";
            case "OTHER"     -> "Autre";
            default          -> code;
        };
    }
}
