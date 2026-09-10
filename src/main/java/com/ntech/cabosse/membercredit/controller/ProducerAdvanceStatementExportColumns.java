package com.ntech.cabosse.membercredit.controller;

import com.ntech.cabosse.membercredit.dto.ProducerAdvanceStatementDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'état des avances producteurs. */
final class ProducerAdvanceStatementExportColumns {

    private ProducerAdvanceStatementExportColumns() {}

    static List<ExportColumn<ProducerAdvanceStatementDto.Row>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),  ProducerAdvanceStatementDto.Row::memberCode),
                ExportColumn.of(Messages.msg("m.imp-h-producteur"),     ProducerAdvanceStatementDto.Row::memberName),
                ExportColumn.of(Messages.msg("m.imp-h-section"),        ProducerAdvanceStatementDto.Row::sectionName),
                ExportColumn.of(Messages.msg("m.exp-h-avances-recues"), ProducerAdvanceStatementDto.Row::advancedAmount),
                ExportColumn.of(Messages.msg("m.imp-h-rembourse-amount"), ProducerAdvanceStatementDto.Row::retainedAmount),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),       ProducerAdvanceStatementDto.Row::weightKg),
                ExportColumn.of(Messages.msg("m.exp-h-valeur-livree"),  ProducerAdvanceStatementDto.Row::delivered),
                ExportColumn.of(Messages.msg("m.exp-h-solde-avance"),   ProducerAdvanceStatementDto.Row::advanceBalance),
                ExportColumn.of(Messages.msg("m.exp-h-du-au-producteur"), ProducerAdvanceStatementDto.Row::owedToProducer));
    }
}
