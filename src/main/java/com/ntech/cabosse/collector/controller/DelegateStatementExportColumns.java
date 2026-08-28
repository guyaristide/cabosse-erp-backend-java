package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateStatementDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Colonnes de l'état des délégués.
 *
 * <p>L'export porte les deux grandeurs, mise en compte et marge, quelle que
 * soit la lecture choisie à l'écran : un fichier tronqué selon l'onglet
 * ouvert obligerait à exporter deux fois pour obtenir le même tableau.</p>
 */
final class DelegateStatementExportColumns {

    private DelegateStatementExportColumns() {}

    static List<ExportColumn<DelegateStatementDto.Row>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),                DelegateStatementDto.Row::delegateCode),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),             DelegateStatementDto.Row::delegateName),
                ExportColumn.of(Messages.msg("m.imp-h-section"),             DelegateStatementDto.Row::sectionName),
                ExportColumn.of(Messages.msg("m.exp-h-mise-en-compte-kg"),   DelegateStatementDto.Row::retentionPerKgFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-mise-en-compte"),      DelegateStatementDto.Row::retentionAmountFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-marge-kg"),            DelegateStatementDto.Row::marginPerKgFcfa),
                ExportColumn.of(Messages.msg("m.exp-h-marge"),               DelegateStatementDto.Row::marginAmountFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),            DelegateStatementDto.Row::weightKg),
                ExportColumn.of(Messages.msg("m.exp-h-valeur-livree"),       DelegateStatementDto.Row::deliveredFcfa));
    }
}
