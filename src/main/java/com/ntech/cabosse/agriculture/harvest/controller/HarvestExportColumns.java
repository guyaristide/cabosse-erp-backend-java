package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des récoltes. */
final class HarvestExportColumns {

    private HarvestExportColumns() {}

    static List<ExportColumn<HarvestResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",               HarvestResponseDto::code),
                ExportColumn.of("Date",               HarvestResponseDto::harvestDate),
                ExportColumn.of("Parcelle",           HarvestResponseDto::parcelCode),
                ExportColumn.of("Producteur",         HarvestResponseDto::memberName),
                ExportColumn.of("Campagne",           HarvestResponseDto::campaignLabel),
                ExportColumn.of("Cabosses (kg)",      HarvestResponseDto::cabossesKg),
                ExportColumn.of("Fèves fraîches (kg)", HarvestResponseDto::freshBeansKg),
                ExportColumn.of("Qualité",            HarvestResponseDto::qualityNotes),
                ExportColumn.of("Notes",              HarvestResponseDto::notes));
    }
}
