package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateLedgerDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes du suivi détaillé d'un délégué, dans l'ordre A à I de l'état. */
final class DelegateLedgerExportColumns {

    private DelegateLedgerExportColumns() {}

    static List<ExportColumn<DelegateLedgerDto.Line>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"),           DelegateLedgerDto.Line::date),
                ExportColumn.of(Messages.msg("m.exp-h-operation"),      l -> l.operation() != null ? l.operation().name() : null),
                ExportColumn.of(Messages.msg("m.imp-h-reference"),      DelegateLedgerDto.Line::ref),
                ExportColumn.of(Messages.msg("m.exp-h-no-brousse"),     DelegateLedgerDto.Line::fieldNoteRef),
                ExportColumn.of(Messages.msg("m.exp-h-avances-cumulees"),   DelegateLedgerDto.Line::advancedFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-solde-brut"),     DelegateLedgerDto.Line::grossBalanceFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),       DelegateLedgerDto.Line::weightKg),
                ExportColumn.of(Messages.msg("m.exp-h-prix-moyen"),     DelegateLedgerDto.Line::averagePricePerKgFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-valeur-livree"),  DelegateLedgerDto.Line::deliveredFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-mise-en-compte"), DelegateLedgerDto.Line::retentionFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-solde-net"),      DelegateLedgerDto.Line::netBalanceFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-taux-remboursement"), DelegateLedgerDto.Line::repaymentRatePct));
    }
}
