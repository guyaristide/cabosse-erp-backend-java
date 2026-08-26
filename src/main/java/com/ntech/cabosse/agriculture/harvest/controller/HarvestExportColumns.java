package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Colonnes de l'export des récoltes.
 *
 * <p>Alignées sur le modèle d'import pour que le fichier fasse l'aller-retour :
 * « Code récolte » plutôt que « Code », que l'import relirait comme un code
 * plantation, et « Nom du producteur » plutôt que « Producteur », que l'import
 * relirait comme un code producteur. Le rapprochement au réimport se fait par
 * (parcelle, date) : ces deux colonnes portent l'identité.</p>
 */
final class HarvestExportColumns {

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private HarvestExportColumns() {}

    static List<ExportColumn<HarvestResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code récolte",       HarvestResponseDto::code),
                ExportColumn.of("Date de récolte", h ->
                        h.harvestDate() == null ? null : h.harvestDate().format(FR_DATE)),
                ExportColumn.of("Code plantation",    HarvestResponseDto::parcelCode),
                ExportColumn.of("Nom de la parcelle", HarvestResponseDto::parcelName),
                ExportColumn.of("Nom du producteur",  HarvestResponseDto::memberName),
                ExportColumn.of("Campagne",           HarvestResponseDto::campaignLabel),
                ExportColumn.of("Cabosses (kg)",      HarvestResponseDto::cabossesKg),
                ExportColumn.of("Fèves fraîches (kg)", HarvestResponseDto::freshBeansKg),
                ExportColumn.of("Qualité",            HarvestResponseDto::qualityNotes),
                ExportColumn.of("Notes",              HarvestResponseDto::notes));
    }
}
