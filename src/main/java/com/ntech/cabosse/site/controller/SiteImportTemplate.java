package com.ntech.cabosse.site.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
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
                ExportColumn.of(Messages.msg("m.imp-h-type"),                   TemplateRow::type),
                ExportColumn.of(Messages.msg("m.imp-h-code"),                   TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),                   TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-address"),                TemplateRow::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),                   TemplateRow::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-region"),                 TemplateRow::regionCode),
                ExportColumn.of(Messages.msg("m.imp-h-country"),                TemplateRow::countryCode),
                ExportColumn.of(Messages.msg("m.imp-h-latitude"),               TemplateRow::latitude),
                ExportColumn.of(Messages.msg("m.imp-h-longitude"),              TemplateRow::longitude),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),                  TemplateRow::phone),
                ExportColumn.of(Messages.msg("m.imp-h-email"),                  TemplateRow::email),
                ExportColumn.of(Messages.msg("m.imp-h-site-manager"),           TemplateRow::managerName),
                ExportColumn.of(Messages.msg("m.imp-h-site-opening-hours"),     TemplateRow::openingHours),
                ExportColumn.of(Messages.msg("m.imp-h-description"),            TemplateRow::description)
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
