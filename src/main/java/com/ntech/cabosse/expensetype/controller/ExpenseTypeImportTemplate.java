package com.ntech.cabosse.expensetype.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

final class ExpenseTypeImportTemplate {

    private ExpenseTypeImportTemplate() {}

    record TemplateRow(String code, String name, String category, String syscohada, String description) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),                     TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),                     TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-expense-type-category"),    TemplateRow::category),
                ExportColumn.of(Messages.msg("m.imp-h-expense-type-account"),     TemplateRow::syscohada),
                ExportColumn.of(Messages.msg("m.imp-h-description"),              TemplateRow::description)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow("transport", "Transport inter-sites", "Logistique", "624", ""),
                new TemplateRow("", "Électricité", "Services", "606", "Courant CIE atelier"),
                new TemplateRow("", "Frais bancaires", "Financier", "627", "")
        );
        return new ExportDataset<>("Modèle d'import types de dépense", cols, samples);
    }
}
