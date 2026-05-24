package com.ntech.cabosse.expensetype.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

final class ExpenseTypeImportTemplate {

    private ExpenseTypeImportTemplate() {}

    record TemplateRow(String code, String name, String category, String syscohada, String description) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("Code",             TemplateRow::code),
                ExportColumn.of("Nom",              TemplateRow::name),
                ExportColumn.of("Catégorie",        TemplateRow::category),
                ExportColumn.of("Compte SYSCOHADA", TemplateRow::syscohada),
                ExportColumn.of("Description",      TemplateRow::description)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow("transport", "Transport inter-sites", "Logistique", "624", ""),
                new TemplateRow("", "Électricité", "Services", "606", "Courant CIE atelier"),
                new TemplateRow("", "Frais bancaires", "Financier", "627", "")
        );
        return new ExportDataset<>("Modèle d'import types de dépense", cols, samples);
    }
}
