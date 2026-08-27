package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle de fichier d'import des parcelles.
 *
 * <p>Pas de colonne de contour : le tracé se fait sur la carte. Les deux
 * exemples montrent les deux écritures de coordonnées acceptées, décimale
 * et degrés minutes secondes, parce que les relevés terrain circulent dans
 * les deux formats.</p>
 */
final class ParcelImportTemplate {

    private ParcelImportTemplate() {}

    record TemplateRow(
            String code, String name, String producerCode, String producerName,
            String surfaceHa, String latitude, String longitude,
            String crop, String mainCrop, String variety,
            String plantingDate, String region, String department,
            String status, String estimateKg, String yieldPerHa, String notes
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-parcel-code"),     TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-name"),  TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),     TemplateRow::producerCode),
                ExportColumn.of(Messages.msg("m.imp-h-producer-name"),   TemplateRow::producerName),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-surface"),     TemplateRow::surfaceHa),
                ExportColumn.of(Messages.msg("m.imp-h-latitude"),            TemplateRow::latitude),
                ExportColumn.of(Messages.msg("m.imp-h-longitude"),           TemplateRow::longitude),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-crop"),             TemplateRow::crop),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-main-crop"),  TemplateRow::mainCrop),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-variety"),             TemplateRow::variety),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-planting-date"),  TemplateRow::plantingDate),
                ExportColumn.of(Messages.msg("m.imp-h-region"),              TemplateRow::region),
                ExportColumn.of(Messages.msg("m.imp-h-department"),         TemplateRow::department),
                ExportColumn.of(Messages.msg("m.imp-h-status"),              TemplateRow::status),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-estimate"),     TemplateRow::estimateKg),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-yield"),   TemplateRow::yieldPerHa),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),               TemplateRow::notes)
        );

        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "", "Parcelle famille N'Guessan", "MB-2026-0001", "N'Guessan Konan",
                        "15,2", "5.236830", "-4.020996",
                        "Cacao", "Oui", "Mercedes",
                        "02/10/2003", "Bas-Sassandra", "Soubré",
                        "En production", "9081", "597", ""),
                new TemplateRow(
                        "PR-2026-0042", "Parcelle route Méagui", "", "Doumbia Seydou",
                        "1,5", "5°32'46.3\"N", "6°38'12.5\"O",
                        "Café", "Non", "",
                        "2015", "Nawa", "Méagui",
                        "En jachère", "", "", "Replantation prévue")
        );
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-parcelles"), cols, samples);
    }
}
