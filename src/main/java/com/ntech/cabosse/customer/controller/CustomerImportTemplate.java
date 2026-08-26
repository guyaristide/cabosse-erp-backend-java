package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

final class CustomerImportTemplate {

    private CustomerImportTemplate() {}

    record TemplateRow(
            String code, String name, String type, String legalName, String taxNumber,
            String email, String phone, String addressLine, String cityName, String countryCode,
            String contactName, String creditLimit, String notes
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),                    TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),                    TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-type"),                    TemplateRow::type),
                ExportColumn.of(Messages.msg("m.imp-h-legal-name"),              TemplateRow::legalName),
                ExportColumn.of(Messages.msg("m.imp-h-tax-number"),              TemplateRow::taxNumber),
                ExportColumn.of(Messages.msg("m.imp-h-email"),                   TemplateRow::email),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),                   TemplateRow::phone),
                ExportColumn.of(Messages.msg("m.imp-h-address"),                 TemplateRow::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),                    TemplateRow::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-country"),                 TemplateRow::countryCode),
                ExportColumn.of(Messages.msg("m.imp-h-contact"),                 TemplateRow::contactName),
                ExportColumn.of(Messages.msg("m.imp-h-customer-credit-limit"),   TemplateRow::creditLimit),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),                   TemplateRow::notes)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "patisserie-louis", "Pâtisserie Louis", "Entreprise",
                        "SARL Pâtisserie Louis", "CI-RCCM-2020-B-67890",
                        "compta@patisserie-louis.ci", "+225 27 21 45 67",
                        "Plateau, rue 7", "Abidjan", "CI",
                        "Mme Toure", "2500000", "Bon payeur"
                ),
                new TemplateRow(
                        "", "Adjoua Konan", "Particulier",
                        "", "", "", "+225 07 12 34 56",
                        "", "Méagui", "CI",
                        "", "", ""
                )
        );
        return new ExportDataset<>("Modèle d'import clients", cols, samples);
    }
}
