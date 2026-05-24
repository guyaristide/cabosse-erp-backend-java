package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

final class SupplierImportTemplate {

    private SupplierImportTemplate() {}

    record TemplateRow(
            String code, String name, String legalName, String taxNumber,
            String email, String phone, String addressLine, String cityName, String countryCode,
            String contactName, String paymentTerms, String notes
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("Code",             TemplateRow::code),
                ExportColumn.of("Nom",              TemplateRow::name),
                ExportColumn.of("Raison sociale",   TemplateRow::legalName),
                ExportColumn.of("N° fiscal",        TemplateRow::taxNumber),
                ExportColumn.of("E-mail",           TemplateRow::email),
                ExportColumn.of("Téléphone",        TemplateRow::phone),
                ExportColumn.of("Adresse",          TemplateRow::addressLine),
                ExportColumn.of("Ville",            TemplateRow::cityName),
                ExportColumn.of("Pays",             TemplateRow::countryCode),
                ExportColumn.of("Contact",          TemplateRow::contactName),
                ExportColumn.of("Conditions paiement", TemplateRow::paymentTerms),
                ExportColumn.of("Notes",            TemplateRow::notes)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "scoops-cacao", "SCOOPS Cacao", "Société Coopérative Cacao",
                        "CI-RCCM-2018-B-12345", "contact@scoops-cacao.ci", "+225 27 34 78 92",
                        "Rue 12, Méagui", "Méagui", "CI",
                        "M. Konan", "30j fin de mois", ""
                ),
                new TemplateRow(
                        "", "Emballages Pro CI", "",
                        "", "ventes@emballagespro.ci", "",
                        "Zone industrielle Yopougon", "Abidjan", "CI",
                        "", "Comptant", ""
                )
        );
        return new ExportDataset<>("Modèle d'import fournisseurs", cols, samples);
    }
}
