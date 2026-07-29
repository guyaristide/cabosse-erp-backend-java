package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle de fichier d'import des membres-producteurs.
 *
 * <p>Les en-têtes sont ceux de la plateforme, mais l'import accepte les
 * variantes courantes : un fichier existant se charge sans être renommé
 * colonne par colonne. Les deux lignes d'exemple montrent les formats
 * attendus, dont les réponses par oui ou non et les dates en JJ/MM/AAAA.</p>
 */
final class MemberImportTemplate {

    private MemberImportTemplate() {}

    record TemplateRow(
            String code, String externalCodeType, String externalCode,
            String lastName, String firstName, String gender, String personType,
            String maritalStatus, String birthDate, String birthPlace,
            String idDocType, String idDocNumber, String nationalIdNumber,
            String phone, String village, String section,
            String joinedAt, String partsSocialesAmount,
            String spousesCount, String childrenCount, String girlsCount, String boysCount,
            String children0to4, String children5to17, String childrenOver17,
            String childrenSchooled, String childrenNotSchooled, String childrenActivity,
            String censusRegistered, String producerCardIssued, String dataCollectedAt
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("Code producteur",           TemplateRow::code),
                // Carte délivrée par un tiers (organisme de filière). Le type
                // absent du référentiel est créé et déclaré comme servant à
                // retrouver le producteur ; laissez les deux vides si votre
                // filière n'en délivre pas.
                ExportColumn.of("Type de carte producteur",  TemplateRow::externalCodeType),
                ExportColumn.of("N° carte producteur",       TemplateRow::externalCode),
                ExportColumn.of("Nom",                       TemplateRow::lastName),
                ExportColumn.of("Prénoms",                   TemplateRow::firstName),
                ExportColumn.of("Genre",                     TemplateRow::gender),
                ExportColumn.of("Nature",                    TemplateRow::personType),
                ExportColumn.of("Situation matrimoniale",    TemplateRow::maritalStatus),
                ExportColumn.of("Date de naissance",         TemplateRow::birthDate),
                ExportColumn.of("Lieu de naissance",         TemplateRow::birthPlace),
                ExportColumn.of("Type de pièce",             TemplateRow::idDocType),
                ExportColumn.of("Numéro de pièce",           TemplateRow::idDocNumber),
                ExportColumn.of("Identifiant national",      TemplateRow::nationalIdNumber),
                ExportColumn.of("Téléphone",                 TemplateRow::phone),
                ExportColumn.of("Village",                   TemplateRow::village),
                ExportColumn.of("Section",                   TemplateRow::section),
                ExportColumn.of("Date d'adhésion",           TemplateRow::joinedAt),
                ExportColumn.of("Parts sociales",            TemplateRow::partsSocialesAmount),
                ExportColumn.of("Nombre de femmes",          TemplateRow::spousesCount),
                ExportColumn.of("Nombre d'enfants",          TemplateRow::childrenCount),
                ExportColumn.of("Filles",                    TemplateRow::girlsCount),
                ExportColumn.of("Garçons",                   TemplateRow::boysCount),
                ExportColumn.of("Enfants 0 à 4 ans",         TemplateRow::children0to4),
                ExportColumn.of("Enfants 5 à 17 ans",        TemplateRow::children5to17),
                ExportColumn.of("Enfants plus de 17 ans",    TemplateRow::childrenOver17),
                ExportColumn.of("Enfants scolarisés",        TemplateRow::childrenSchooled),
                ExportColumn.of("Enfants non scolarisés",    TemplateRow::childrenNotSchooled),
                ExportColumn.of("Activité des enfants",      TemplateRow::childrenActivity),
                ExportColumn.of("Producteur recensé",        TemplateRow::censusRegistered),
                ExportColumn.of("Carte producteur remise",   TemplateRow::producerCardIssued),
                ExportColumn.of("Date de collecte",          TemplateRow::dataCollectedAt)
        );

        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "", "Carte producteur", "CCC-2021-183667",
                        "N'Guessan", "Konan", "Homme", "Personne physique",
                        "Marié(e)", "19/10/1962", "Sakassou",
                        "Carte nationale d'identité", "CI60013389083", "863794026542",
                        "0551161559", "Méagui", "Section Méagui",
                        "01/03/2020", "25000",
                        "1", "7", "1", "6",
                        "6", "0", "1",
                        "0", "0", "Aucune activité",
                        "Oui", "Oui", "25/01/2026"),
                new TemplateRow(
                        "MB-2026-0002", "", "",
                        "Doumbia", "Seydou", "Homme", "",
                        "Célibataire", "1985", "Soubré",
                        "CNI", "CI70011122334", "",
                        "0707080910", "Soubré", "Section Soubré",
                        "", "",
                        "", "", "", "",
                        "", "", "",
                        "", "", "",
                        "Non", "Non", "")
        );
        return new ExportDataset<>("Modèle d'import membres-producteurs", cols, samples);
    }
}
