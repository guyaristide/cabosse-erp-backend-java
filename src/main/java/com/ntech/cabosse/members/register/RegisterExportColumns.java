package com.ntech.cabosse.members.register;

import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Colonnes du registre producteurs (backlog REG-01), dans l'ordre exact du
 * fichier modèle « MODELE REGISTRE COOPERATIVE ».
 */
final class RegisterExportColumns {

    private RegisterExportColumns() {}

    static List<ExportColumn<RegisterRow>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-n"), RegisterRow::no),
                ExportColumn.of(Messages.msg("m.imp-h-nom-cooperative"), RegisterRow::coopName),
                ExportColumn.of(Messages.msg("m.imp-h-code-interne-producteur"), RegisterRow::producerCode),
                ExportColumn.of(Messages.msg("m.imp-h-code-ccc-producteur"), RegisterRow::externalCode),
                ExportColumn.of(Messages.msg("m.imp-h-nom-prenoms-du-producteurs"), RegisterRow::producerName),
                ExportColumn.of(Messages.msg("m.imp-h-date-de-naissance"), RegisterRow::birth),
                ExportColumn.of(Messages.msg("m.imp-h-member-id-doc-type"), RegisterRow::idDocType),
                ExportColumn.of(Messages.msg("m.imp-h-numero-piece"), RegisterRow::idDocNumber),
                ExportColumn.of(Messages.msg("m.imp-h-sexe"), RegisterRow::sexe),
                ExportColumn.of(Messages.msg("m.imp-h-sections"), RegisterRow::section),
                ExportColumn.of(Messages.msg("m.imp-h-villages-ou-localites"), RegisterRow::village),
                ExportColumn.of(Messages.msg("m.imp-h-code-interne-plantation"), RegisterRow::plantationCode),
                ExportColumn.of(Messages.msg("m.imp-h-annee-de-creation-de-la-plantation"), RegisterRow::plantingYear),
                ExportColumn.of(Messages.msg("m.imp-h-age-plantation"), RegisterRow::age),
                ExportColumn.of("surface-ha", Messages.msg("m.imp-h-superficie-plantation-ha"), ColumnKind.NUMBER_QTY, RegisterRow::surfaceHa),
                ExportColumn.of("rendement", Messages.msg("m.imp-h-rendement-de-la-parcelle"), ColumnKind.NUMBER_QTY, RegisterRow::yieldPerHa),
                ExportColumn.of(Messages.msg("m.imp-h-estimation-production-parcelle-pour-la-campagn"), RegisterRow::estimateKg),
                ExportColumn.of("latitude", Messages.msg("m.imp-h-latitude"), ColumnKind.NUMBER_PRECISE, RegisterRow::latitude),
                ExportColumn.of("longitude", Messages.msg("m.imp-h-longitude"), ColumnKind.NUMBER_PRECISE, RegisterRow::longitude),
                ExportColumn.of(Messages.msg("m.imp-h-department"), RegisterRow::department),
                ExportColumn.of(Messages.msg("m.imp-h-regions"), RegisterRow::region),
                ExportColumn.of(Messages.msg("m.imp-h-noms-prenoms-agent-charge-du-suivi-du-producte"),
                        RegisterRow::agentName),
                ExportColumn.of(Messages.msg("m.imp-h-contact-pr"), RegisterRow::agentContact)
        );
    }
}
