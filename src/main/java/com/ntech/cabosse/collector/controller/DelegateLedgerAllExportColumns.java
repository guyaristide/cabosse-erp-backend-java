package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateLedgerRowDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Colonnes du suivi détaillé de tous les délégués dans un seul fichier.
 *
 * <p>Mêmes colonnes que le suivi d'un délégué, précédées de qui il est :
 * sans elles, les lignes de douze délégués seraient indiscernables.</p>
 */
final class DelegateLedgerAllExportColumns {

    private DelegateLedgerAllExportColumns() {}

    static List<ExportColumn<DelegateLedgerRowDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),                DelegateLedgerRowDto::delegateCode),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),             DelegateLedgerRowDto::delegateName),
                ExportColumn.of(Messages.msg("m.imp-h-section"),             DelegateLedgerRowDto::sectionName),
                ExportColumn.of(Messages.msg("m.imp-h-date"),                DelegateLedgerRowDto::date),
                ExportColumn.of(Messages.msg("m.exp-h-operation"),           DelegateLedgerRowDto::operation),
                ExportColumn.of(Messages.msg("m.imp-h-reference"),           DelegateLedgerRowDto::ref),
                ExportColumn.of(Messages.msg("m.exp-h-no-brousse"),          DelegateLedgerRowDto::fieldNoteRef),
                ExportColumn.of(Messages.msg("m.exp-h-avances-cumulees"),    DelegateLedgerRowDto::advanced),
                ExportColumn.of(Messages.msg("m.exp-h-solde-brut"),          DelegateLedgerRowDto::grossBalance),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),            DelegateLedgerRowDto::weightKg),
                ExportColumn.of(Messages.msg("m.exp-h-prix-moyen"),          DelegateLedgerRowDto::averagePricePerKg),
                ExportColumn.of(Messages.msg("m.exp-h-valeur-livree"),       DelegateLedgerRowDto::delivered),
                ExportColumn.of(Messages.msg("m.exp-h-mise-en-compte"),      DelegateLedgerRowDto::retention),
                ExportColumn.of(Messages.msg("m.exp-h-solde-net"),           DelegateLedgerRowDto::netBalance),
                ExportColumn.of(Messages.msg("m.exp-h-taux-remboursement"),  DelegateLedgerRowDto::repaymentRatePct));
    }
}
