package com.ntech.cabosse.site.controller;

import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.site.dto.SiteResponseDto;

import java.util.List;

final class SiteExportColumns {

    private SiteExportColumns() {}

    static List<ExportColumn<SiteResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),        SiteResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),         SiteResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-type"),        s -> humanType(s.type())),
                ExportColumn.of(Messages.msg("m.imp-h-address"),     SiteResponseDto::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),       SiteResponseDto::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-region"),      SiteResponseDto::regionCode),
                ExportColumn.of(Messages.msg("m.imp-h-country"),        SiteResponseDto::countryCode),
                ExportColumn.of("latitude", Messages.msg("m.imp-h-latitude"), ColumnKind.NUMBER_PRECISE,    SiteResponseDto::latitude),
                ExportColumn.of("longitude", Messages.msg("m.imp-h-longitude"), ColumnKind.NUMBER_PRECISE,   SiteResponseDto::longitude),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),   SiteResponseDto::phone),
                ExportColumn.of(Messages.msg("m.imp-h-email"),      SiteResponseDto::email),
                ExportColumn.of(Messages.msg("m.imp-h-site-manager"), SiteResponseDto::managerName),
                ExportColumn.of(Messages.msg("m.imp-h-site-opening-hours"),    SiteResponseDto::openingHours),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),       SiteResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-description"), SiteResponseDto::description)
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
