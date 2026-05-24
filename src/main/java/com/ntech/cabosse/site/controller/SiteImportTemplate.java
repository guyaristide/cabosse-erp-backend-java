package com.ntech.cabosse.site.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

final class SiteImportTemplate {

    private SiteImportTemplate() {}

    record TemplateRow(
            String type, String code, String name,
            String addressLine, String cityName, String regionCode, String countryCode,
            String latitude, String longitude,
            String phone, String email, String managerName, String openingHours, String description
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("Type",         TemplateRow::type),
                ExportColumn.of("Code",         TemplateRow::code),
                ExportColumn.of("Nom",          TemplateRow::name),
                ExportColumn.of("Adresse",      TemplateRow::addressLine),
                ExportColumn.of("Ville",        TemplateRow::cityName),
                ExportColumn.of("Région",       TemplateRow::regionCode),
                ExportColumn.of("Pays",         TemplateRow::countryCode),
                ExportColumn.of("Latitude",     TemplateRow::latitude),
                ExportColumn.of("Longitude",    TemplateRow::longitude),
                ExportColumn.of("Téléphone",    TemplateRow::phone),
                ExportColumn.of("E-mail",       TemplateRow::email),
                ExportColumn.of("Responsable",  TemplateRow::managerName),
                ExportColumn.of("Horaires",     TemplateRow::openingHours),
                ExportColumn.of("Description",  TemplateRow::description)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "Transformation", "meagui-atelier", "Atelier Méagui",
                        "Route nationale 7", "Méagui", "BAS", "CI",
                        "5.5462", "-7.4528",
                        "+225 27 34 78 90", "atelier@cabosse.ci",
                        "M. Konan", "Lun-Ven 7h-17h", ""
                ),
                new TemplateRow(
                        "Point de vente", "", "Boutique Plateau",
                        "Avenue Houphouët-Boigny", "Abidjan", "LAG", "CI",
                        "", "",
                        "+225 27 21 22 33", "", "", "Lun-Sam 9h-19h", ""
                )
        );
        return new ExportDataset<>("Modèle d'import sites", cols, samples);
    }
}
