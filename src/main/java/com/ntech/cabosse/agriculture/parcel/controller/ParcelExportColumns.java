package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export du parcellaire. */
final class ParcelExportColumns {

    private ParcelExportColumns() {}

    static List<ExportColumn<ParcelResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",                ParcelResponseDto::code),
                ExportColumn.of("Nom",                 ParcelResponseDto::name),
                ExportColumn.of("Producteur",          ParcelResponseDto::memberName),
                ExportColumn.of("Surface (ha)",        ParcelResponseDto::surfaceHa),
                ExportColumn.of("Culture",             ParcelResponseDto::cropCode),
                ExportColumn.of("Variété",             ParcelResponseDto::variety),
                ExportColumn.of("Année de plantation", ParcelResponseDto::plantingYear),
                ExportColumn.of("Région",              ParcelResponseDto::regionCode),
                ExportColumn.of("Département",         ParcelResponseDto::departmentCode),
                ExportColumn.of("Statut",              ParcelResponseDto::status),
                ExportColumn.of("Certifications", p ->
                        p.certifications() == null ? null : String.join(", ", p.certifications())));
    }
}
