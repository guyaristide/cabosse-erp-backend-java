package com.ntech.cabosse.members.register;

import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/**
 * Colonnes du registre producteurs (backlog REG-01), dans l'ordre exact du
 * fichier modèle « MODELE REGISTRE COOPERATIVE ».
 */
final class RegisterExportColumns {

    private RegisterExportColumns() {}

    static List<ExportColumn<RegisterRow>> all() {
        return List.of(
                ExportColumn.of("N°", RegisterRow::no),
                ExportColumn.of("Nom coopérative", RegisterRow::coopName),
                ExportColumn.of("Code interne producteur", RegisterRow::producerCode),
                ExportColumn.of("Code CCC producteur", RegisterRow::externalCode),
                ExportColumn.of("Nom & Prénoms du producteurs", RegisterRow::producerName),
                ExportColumn.of("Date de Naissance", RegisterRow::birth),
                ExportColumn.of("Type de pièce", RegisterRow::idDocType),
                ExportColumn.of("Numero pièce", RegisterRow::idDocNumber),
                ExportColumn.of("Sexe", RegisterRow::sexe),
                ExportColumn.of("Sections", RegisterRow::section),
                ExportColumn.of("Villages ou localités", RegisterRow::village),
                ExportColumn.of("Code interne Plantation", RegisterRow::plantationCode),
                ExportColumn.of("Année de création de la plantation", RegisterRow::plantingYear),
                ExportColumn.of("Age Plantation", RegisterRow::age),
                ExportColumn.of("surface-ha", "Superficie Plantation (ha)", ColumnKind.NUMBER_QTY, RegisterRow::surfaceHa),
                ExportColumn.of("rendement", "Rendement de la parcelle", ColumnKind.NUMBER_QTY, RegisterRow::yieldPerHa),
                ExportColumn.of("Estimation production parcelle pour la campagne", RegisterRow::estimateKg),
                ExportColumn.of("latitude", "Latitude", ColumnKind.NUMBER_PRECISE, RegisterRow::latitude),
                ExportColumn.of("longitude", "longitude", ColumnKind.NUMBER_PRECISE, RegisterRow::longitude),
                ExportColumn.of("Département", RegisterRow::department),
                ExportColumn.of("Régions", RegisterRow::region),
                ExportColumn.of("Noms & Prénoms Agent chargé du suivi du producteur en section (PR)",
                        RegisterRow::agentName),
                ExportColumn.of("Contact PR", RegisterRow::agentContact)
        );
    }
}
