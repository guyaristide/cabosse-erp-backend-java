package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
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
                ExportColumn.of("Code plantation",     TemplateRow::code),
                ExportColumn.of("Nom de la parcelle",  TemplateRow::name),
                ExportColumn.of("Code producteur",     TemplateRow::producerCode),
                ExportColumn.of("Nom du producteur",   TemplateRow::producerName),
                ExportColumn.of("Superficie (ha)",     TemplateRow::surfaceHa),
                ExportColumn.of("Latitude",            TemplateRow::latitude),
                ExportColumn.of("Longitude",           TemplateRow::longitude),
                ExportColumn.of("Culture",             TemplateRow::crop),
                ExportColumn.of("Culture principale",  TemplateRow::mainCrop),
                ExportColumn.of("Variété",             TemplateRow::variety),
                ExportColumn.of("Date de plantation",  TemplateRow::plantingDate),
                ExportColumn.of("Région",              TemplateRow::region),
                ExportColumn.of("Département",         TemplateRow::department),
                ExportColumn.of("Statut",              TemplateRow::status),
                ExportColumn.of("Estimation (kg)",     TemplateRow::estimateKg),
                ExportColumn.of("Rendement (kg/ha)",   TemplateRow::yieldPerHa),
                ExportColumn.of("Notes",               TemplateRow::notes)
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
        return new ExportDataset<>("Modèle d'import parcelles", cols, samples);
    }
}
