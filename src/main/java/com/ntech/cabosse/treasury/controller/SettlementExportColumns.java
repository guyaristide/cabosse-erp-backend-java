package com.ntech.cabosse.treasury.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.treasury.dto.SettlementDto;

import java.util.List;

/**
 * Colonnes de l'état des règlements exécutés.
 *
 * <p>Les frais bancaires occupent leur propre colonne, jamais fondus dans
 * le montant : les additionner ferait croire que le bénéficiaire a reçu
 * moins qu'il n'a reçu, et le rapprochement avec la banque cesserait de
 * tomber juste.</p>
 *
 * <p>La référence de règlement sort telle qu'elle a été saisie. Un numéro
 * de chèque se recopie sur un talon, il ne se reformate pas.</p>
 */
final class SettlementExportColumns {

    private SettlementExportColumns() {}

    static List<ExportColumn<SettlementDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"), SettlementDto::settledAt),
                ExportColumn.of(Messages.msg("m.imp-h-nature-du-reglement"), SettlementDto::kind),
                ExportColumn.of(Messages.msg("m.imp-h-reference-source"), SettlementDto::sourceRef),
                ExportColumn.of(Messages.msg("m.imp-h-beneficiaire"),
                        SettlementDto::beneficiaryName),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"), SettlementDto::amount),
                ExportColumn.of(Messages.msg("m.imp-h-frais-bancaires"),
                        SettlementDto::bankFees),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-method"),
                        SettlementDto::paymentMethod),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-ref"),
                        SettlementDto::paymentRef),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"), SettlementDto::pieceRef),
                ExportColumn.of(Messages.msg("m.imp-h-execute-par"),
                        s -> s.settledByName() != null ? s.settledByName() : s.settledByEmail()));
    }
}
