package com.ntech.cabosse.expensetype.controller;

import com.ntech.cabosse.expensetype.dto.ExpenseTypeResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

final class ExpenseTypeExportColumns {

    private ExpenseTypeExportColumns() {}

    static List<ExportColumn<ExpenseTypeResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",             ExpenseTypeResponseDto::code),
                ExportColumn.of("Nom",              ExpenseTypeResponseDto::name),
                ExportColumn.of("Catégorie",        e -> humanCategory(e.category())),
                ExportColumn.of("Compte SYSCOHADA", ExpenseTypeResponseDto::syscohadaAccount),
                ExportColumn.of("Actif",            ExpenseTypeResponseDto::active),
                ExportColumn.of("Description",      ExpenseTypeResponseDto::description)
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
