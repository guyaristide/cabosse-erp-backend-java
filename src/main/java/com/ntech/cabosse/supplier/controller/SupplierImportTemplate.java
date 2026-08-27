package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
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
                ExportColumn.of(Messages.msg("m.imp-h-code"),                        TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),                        TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-legal-name"),                  TemplateRow::legalName),
                ExportColumn.of(Messages.msg("m.imp-h-tax-number"),                  TemplateRow::taxNumber),
                ExportColumn.of(Messages.msg("m.imp-h-email"),                       TemplateRow::email),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),                       TemplateRow::phone),
                ExportColumn.of(Messages.msg("m.imp-h-address"),                     TemplateRow::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),                        TemplateRow::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-country"),                     TemplateRow::countryCode),
                ExportColumn.of(Messages.msg("m.imp-h-contact"),                     TemplateRow::contactName),
                ExportColumn.of(Messages.msg("m.imp-h-supplier-payment-terms"),      TemplateRow::paymentTerms),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),                       TemplateRow::notes)
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
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-fournisseurs"), cols, samples);
    }
}
