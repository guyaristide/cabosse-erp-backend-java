package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateStatementDto;
import com.ntech.cabosse.shared.export.ColumnKind;
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
                // Chaque nombre déclare sa nature : le writer devinait un
                // pourcentage dès que l'en-tête disait « marge », et la marge
                // du délégué sortait « 15,0 % » au lieu de 15 FCFA/kg (29/10/2026).
                ExportColumn.of("solde-depart", Messages.msg("m.exp-h-solde-depart"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::openingBalance),
                ExportColumn.of("avances-recues", Messages.msg("m.exp-h-avances-recues"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::advancedAmount),
                ExportColumn.of("mise-en-compte-kg", Messages.msg("m.exp-h-mise-en-compte-kg"),
                        ColumnKind.NUMBER_QTY, DelegateStatementDto.Row::retentionPerKg),
                ExportColumn.of("mise-en-compte", Messages.msg("m.exp-h-mise-en-compte"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::retentionAmount),
                ExportColumn.of("marge-kg", Messages.msg("m.exp-h-marge-kg"),
                        ColumnKind.NUMBER_QTY, DelegateStatementDto.Row::marginPerKg),
                ExportColumn.of("marge", Messages.msg("m.exp-h-marge"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::marginAmount),
                ExportColumn.of("poids-kg", Messages.msg("m.imp-h-poids-kg"),
                        ColumnKind.NUMBER_QTY, DelegateStatementDto.Row::weightKg),
                ExportColumn.of("valeur-livree", Messages.msg("m.exp-h-valeur-livree"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::delivered),
                ExportColumn.of("solde-avance", Messages.msg("m.exp-h-solde-avance"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::advanceBalance),
                ExportColumn.of("du-au-delegue", Messages.msg("m.exp-h-du-au-delegue"),
                        ColumnKind.NUMBER_MONEY, DelegateStatementDto.Row::owedToDelegate));
    }
}
