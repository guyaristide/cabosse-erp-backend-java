package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
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
            String censusRegistered, String producerCardIssued, String dataCollectedAt,
            // Parcelle portée par la ligne : un producteur qui en exploite
            // plusieurs est déclaré sur plusieurs lignes de même code.
            String parcelCode, String parcelName, String parcelSurfaceHa,
            String parcelPotentialKg, String parcelCrop, String parcelVariety,
            String parcelPlantingYear, String parcelLatitude, String parcelLongitude,
            String parcelRegion, String parcelDepartment, String parcelStatus,
            String parcelCertifications,
            String delegateCode
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),                  TemplateRow::code),
                // Carte délivrée par un tiers (organisme de filière). Le type
                // absent du référentiel est créé et déclaré comme servant à
                // retrouver le producteur ; laissez les deux vides si votre
                // filière n'en délivre pas.
                ExportColumn.of(Messages.msg("m.imp-h-member-card-type"),               TemplateRow::externalCodeType),
                ExportColumn.of(Messages.msg("m.imp-h-producer-card-number"),           TemplateRow::externalCode),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),               TemplateRow::lastName),
                ExportColumn.of(Messages.msg("m.imp-h-member-first-name"),              TemplateRow::firstName),
                ExportColumn.of(Messages.msg("m.imp-h-member-gender"),                  TemplateRow::gender),
                ExportColumn.of(Messages.msg("m.imp-h-member-person-type"),             TemplateRow::personType),
                ExportColumn.of(Messages.msg("m.imp-h-member-marital-status"),          TemplateRow::maritalStatus),
                ExportColumn.of(Messages.msg("m.imp-h-member-birth-date"),              TemplateRow::birthDate),
                ExportColumn.of(Messages.msg("m.imp-h-member-birth-place"),             TemplateRow::birthPlace),
                ExportColumn.of(Messages.msg("m.imp-h-member-id-doc-type"),             TemplateRow::idDocType),
                ExportColumn.of(Messages.msg("m.imp-h-member-id-doc-number"),           TemplateRow::idDocNumber),
                ExportColumn.of(Messages.msg("m.imp-h-member-national-id"),             TemplateRow::nationalIdNumber),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),                          TemplateRow::phone),
                ExportColumn.of(Messages.msg("m.imp-h-member-village"),                 TemplateRow::village),
                ExportColumn.of(Messages.msg("m.imp-h-section"),                        TemplateRow::section),
                ExportColumn.of(Messages.msg("m.imp-h-member-joined-at"),               TemplateRow::joinedAt),
                ExportColumn.of(Messages.msg("m.imp-h-member-shares"),                  TemplateRow::partsSocialesAmount),
                ExportColumn.of(Messages.msg("m.imp-h-member-spouses-count"),           TemplateRow::spousesCount),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-count"),          TemplateRow::childrenCount),
                ExportColumn.of(Messages.msg("m.imp-h-member-girls-count"),             TemplateRow::girlsCount),
                ExportColumn.of(Messages.msg("m.imp-h-member-boys-count"),              TemplateRow::boysCount),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-0-4"),            TemplateRow::children0to4),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-5-17"),           TemplateRow::children5to17),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-over-17"),        TemplateRow::childrenOver17),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-schooled"),       TemplateRow::childrenSchooled),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-not-schooled"),   TemplateRow::childrenNotSchooled),
                ExportColumn.of(Messages.msg("m.imp-h-member-children-activity"),       TemplateRow::childrenActivity),
                ExportColumn.of(Messages.msg("m.imp-h-member-census-registered"),       TemplateRow::censusRegistered),
                ExportColumn.of(Messages.msg("m.imp-h-member-card-issued"),             TemplateRow::producerCardIssued),
                ExportColumn.of(Messages.msg("m.imp-h-member-collected-at"),            TemplateRow::dataCollectedAt),
                // ─── Parcelle ─── Laisser « Code plantation » vide à la création : il
                // est rendu par l'export, et le remplir aux imports suivants évite
                // qu'une parcelle soit créée une seconde fois.
                ExportColumn.of(Messages.msg("m.imp-h-parcel-code"),                    TemplateRow::parcelCode),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-name"),                    TemplateRow::parcelName),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-surface"),                 TemplateRow::parcelSurfaceHa),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-potential"),               TemplateRow::parcelPotentialKg),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-crop"),                    TemplateRow::parcelCrop),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-variety"),                 TemplateRow::parcelVariety),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-planting-year"),           TemplateRow::parcelPlantingYear),
                ExportColumn.of(Messages.msg("m.imp-h-latitude"),                       TemplateRow::parcelLatitude),
                ExportColumn.of(Messages.msg("m.imp-h-longitude"),                      TemplateRow::parcelLongitude),
                ExportColumn.of(Messages.msg("m.imp-h-region"),                         TemplateRow::parcelRegion),
                ExportColumn.of(Messages.msg("m.imp-h-department"),                     TemplateRow::parcelDepartment),
                ExportColumn.of(Messages.msg("m.imp-h-status"),                         TemplateRow::parcelStatus),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-certifications"),          TemplateRow::parcelCertifications),
                ExportColumn.of(Messages.msg("m.imp-h-delegate-code"),                  TemplateRow::delegateCode)
        );

        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "", Messages.msg("m.imp-v-producer-card"), "CCC-2021-183667",
                        "N'Guessan", "Konan", Messages.msg("m.imp-v-male"), Messages.msg("m.imp-v-natural-person"),
                        Messages.msg("m.imp-v-married"), "19/10/1962", "Sakassou",
                        Messages.msg("m.imp-v-national-id"), "CI60013389083", "863794026542",
                        "0551161559", "Méagui", "Section Méagui",
                        "01/03/2020", "25000",
                        "1", "7", "1", "6",
                        "6", "0", "1",
                        "0", "0", "Aucune activité",
                        Messages.msg("m.imp-v-yes"), Messages.msg("m.imp-v-yes"), "25/01/2026",
                        // Code plantation vide : la parcelle sera créée. Aux
                        // imports suivants, reprendre celui que l'export rend.
                        "", "Parcelle Sud", "4,5", "3200", "Cacao", "Mercedes",
                        "2012", "5,2361", "-6,6094", "Nawa", "Soubré",
                        Messages.msg("m.imp-v-active"), "Rainforest Alliance",
                        "DEL-001"),
                new TemplateRow(
                        "MB-2026-0002", "", "",
                        "Doumbia", "Seydou", Messages.msg("m.imp-v-male"), "",
                        // Une date complète : le lecteur n'accepte pas l'année seule, et un
                        // modèle qui montre une valeur que son propre import refuse fait
                        // douter l'utilisateur de sa saisie plutôt que de l'exemple.
                        Messages.msg("m.imp-v-single"), "12/06/1985", "Soubré",
                        "CNI", "CI70011122334", "",
                        "0707080910", "Soubré", "Section Soubré",
                        "", "",
                        "", "", "", "",
                        "", "", "",
                        "", "", "",
                        "Non", "Non", "",
                        "", "", "", "", "", "", "", "", "", "", "", "", "", ""),
                // Troisième ligne : la SECONDE parcelle du premier producteur.
                // Un producteur qui exploite plusieurs parcelles se déclare
                // sur plusieurs lignes portant le même code producteur : il
                // est créé une fois, chaque ligne ajoute sa parcelle. Seules
                // les colonnes de parcelle changent.
                new TemplateRow(
                        "", Messages.msg("m.imp-v-producer-card"), "CCC-2021-183667",
                        "N'Guessan", "Konan", "", "", "", "", "",
                        "", "", "", "", "", "",
                        "", "",
                        "", "", "", "",
                        "", "", "",
                        "", "", "",
                        "", "", "",
                        "", "Parcelle Nord", "2,8", "1900", "Cacao", "Forastero",
                        "2016", "5,2504", "-6,5981", "Nawa", "Soubré",
                        Messages.msg("m.imp-v-active"), "",
                        "DEL-001")
        );
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-membres-producteurs"), cols, samples);
    }
}
