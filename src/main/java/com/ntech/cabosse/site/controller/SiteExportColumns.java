package com.ntech.cabosse.site.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.site.dto.SiteResponseDto;

import java.util.List;

final class SiteExportColumns {

    private SiteExportColumns() {}

    static List<ExportColumn<SiteResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",        SiteResponseDto::code),
                ExportColumn.of("Nom",         SiteResponseDto::name),
                ExportColumn.of("Type",        s -> humanType(s.type())),
                ExportColumn.of("Adresse",     SiteResponseDto::addressLine),
                ExportColumn.of("Ville",       SiteResponseDto::cityName),
                ExportColumn.of("Région",      SiteResponseDto::regionCode),
                ExportColumn.of("Pays",        SiteResponseDto::countryCode),
                ExportColumn.of("Latitude",    SiteResponseDto::latitude),
                ExportColumn.of("Longitude",   SiteResponseDto::longitude),
                ExportColumn.of("Téléphone",   SiteResponseDto::phone),
                ExportColumn.of("E-mail",      SiteResponseDto::email),
                ExportColumn.of("Responsable", SiteResponseDto::managerName),
                ExportColumn.of("Horaires",    SiteResponseDto::openingHours),
                ExportColumn.of("Actif",       SiteResponseDto::active),
                ExportColumn.of("Description", SiteResponseDto::description)
        );
    }

    private static String humanType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "TRANSFORMATION"     -> "Transformation";
            case "SALES_POINT"        -> "Point de vente";
            case "SECTION_WAREHOUSE"  -> "Magasin de section";
            case "CENTRAL_WAREHOUSE"  -> "Magasin central";
            default               -> code;
        };
    }
}
