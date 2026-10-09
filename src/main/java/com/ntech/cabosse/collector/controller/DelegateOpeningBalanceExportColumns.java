package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Colonnes de l'export des soldes de début de campagne.
 *
 * <p>Les mêmes que le modèle d'import, et dans le même ordre : le fichier
 * exporté se corrige puis se recharge tel quel, sans réordonner une
 * colonne.</p>
 */
final class DelegateOpeningBalanceExportColumns {

    private DelegateOpeningBalanceExportColumns() {}

    static List<ExportColumn<DelegateOpeningBalanceDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"), DelegateOpeningBalanceDto::delegateCode),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"), DelegateOpeningBalanceDto::delegateName),
                ExportColumn.of(Messages.msg("m.exp-h-solde-depart"), DelegateOpeningBalanceDto::amount),
                ExportColumn.of(Messages.msg("m.imp-h-notes"), DelegateOpeningBalanceDto::notes));
    }
}
