package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
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
                ExportColumn.of("Code",            TemplateRow::code),
                ExportColumn.of("Nom",             TemplateRow::name),
                ExportColumn.of("Type",            TemplateRow::type),
                ExportColumn.of("Raison sociale",  TemplateRow::legalName),
                ExportColumn.of("N° fiscal",       TemplateRow::taxNumber),
                ExportColumn.of("E-mail",          TemplateRow::email),
                ExportColumn.of("Téléphone",       TemplateRow::phone),
                ExportColumn.of("Adresse",         TemplateRow::addressLine),
                ExportColumn.of("Ville",           TemplateRow::cityName),
                ExportColumn.of("Pays",            TemplateRow::countryCode),
                ExportColumn.of("Contact",         TemplateRow::contactName),
                ExportColumn.of("Plafond crédit",  TemplateRow::creditLimit),
                ExportColumn.of("Notes",           TemplateRow::notes)
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
