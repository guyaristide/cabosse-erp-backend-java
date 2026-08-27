package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

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
                ExportColumn.of(Messages.msg("m.imp-h-harvest-code"), HarvestResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-date"), h ->
                        h.harvestDate() == null ? null : h.harvestDate().format(FR_DATE)),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-code"), HarvestResponseDto::parcelCode),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-name"), HarvestResponseDto::parcelName),
                ExportColumn.of(Messages.msg("m.imp-h-producer-name"), HarvestResponseDto::memberName),
                ExportColumn.of(Messages.msg("m.imp-h-campaign"), HarvestResponseDto::campaignLabel),
                ExportColumn.of("cabosses-kg", Messages.msg("m.imp-h-harvest-pods"), ColumnKind.NUMBER_QTY, HarvestResponseDto::cabossesKg),
                ExportColumn.of("feves-fraiches-kg", Messages.msg("m.imp-h-harvest-fresh-beans"), ColumnKind.NUMBER_QTY, HarvestResponseDto::freshBeansKg),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-quality"), HarvestResponseDto::qualityNotes),
                ExportColumn.of(Messages.msg("m.imp-h-notes"), HarvestResponseDto::notes));
    }
}
