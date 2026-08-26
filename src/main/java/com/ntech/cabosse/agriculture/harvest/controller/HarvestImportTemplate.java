package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle de fichier d'import des récoltes.
 *
 * <p>Ni colonne producteur ni colonne campagne : le producteur se déduit de
 * la parcelle, la campagne se choisit à l'écran pour tout le fichier. La
 * colonne producteur reste acceptée en lecture, pour départager deux
 * parcelles homonymes.</p>
 */
final class HarvestImportTemplate {

    private HarvestImportTemplate() {}

    record TemplateRow(
            String parcelCode, String parcelName, String producerCode,
            String harvestDate, String cabossesKg, String freshBeansKg,
            String qualityNotes, String notes
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-parcel-code"),           TemplateRow::parcelCode),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-name"),           TemplateRow::parcelName),
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),         TemplateRow::producerCode),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-date"),          TemplateRow::harvestDate),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-pods"),          TemplateRow::cabossesKg),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-fresh-beans"),   TemplateRow::freshBeansKg),
                ExportColumn.of(Messages.msg("m.imp-h-harvest-quality"),       TemplateRow::qualityNotes),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),                 TemplateRow::notes)
        );

        List<TemplateRow> samples = List.of(
                new TemplateRow("PR-2026-0001", "", "",
                        "12/11/2025", "1250", "480", "Bonne maturité", ""),
                new TemplateRow("", "Parcelle route Méagui", "MB-2026-0002",
                        "18/11/2025", "", "310", "", "Passage après pluie")
        );
        return new ExportDataset<>("Modèle d'import récoltes", cols, samples);
    }
}
