package com.ntech.cabosse.treasury.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.treasury.dto.AccountStatementDtos.MovementDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Colonnes de l'export du relevé d'un compte de trésorerie.
 *
 * <p>Entrée et sortie occupent deux colonnes distinctes, comme sur un
 * relevé bancaire : un montant signé dans une colonne unique se trie mal
 * dans un tableur et se totalise plus mal encore.</p>
 */
final class AccountStatementExportColumns {

    private AccountStatementExportColumns() {}

    static List<ExportColumn<MovementDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"), MovementDto::date),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"), MovementDto::pieceRef),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"), MovementDto::libelle),
                ExportColumn.of(Messages.msg("m.imp-h-operation"), MovementDto::sourceType),
                ExportColumn.of(Messages.msg("m.imp-h-reference-source"), MovementDto::sourceRef),
                ExportColumn.of(Messages.msg("m.imp-h-entree-fcfa"),
                        m -> "IN".equals(m.direction()) ? m.amountFcfa() : BigDecimal.ZERO),
                ExportColumn.of(Messages.msg("m.imp-h-sortie-fcfa"),
                        m -> "OUT".equals(m.direction()) ? m.amountFcfa() : BigDecimal.ZERO),
                ExportColumn.of(Messages.msg("m.imp-h-solde-progressif"), MovementDto::balanceFcfa));
    }
}
