package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.members.dto.MemberStatementDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Les colonnes du fil des opérations d'un membre : date, nature, référence, libellé, kilos, montant, campagne. */
final class MemberStatementExportColumns {
    private MemberStatementExportColumns() {}

    static List<ExportColumn<MemberStatementDto.Operation>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"),            MemberStatementDto.Operation::date),
                ExportColumn.of(Messages.msg("m.imp-h-nature"), op -> operationKind(op.type())),
                ExportColumn.of(Messages.msg("m.imp-h-reference"),       MemberStatementDto.Operation::ref),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),         MemberStatementDto.Operation::label),
                ExportColumn.of(Messages.msg("m.imp-h-poids-kg"),        MemberStatementDto.Operation::quantityKg),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),  MemberStatementDto.Operation::amount),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), MemberStatementDto.Operation::campaignLabel));
    }

    private static String operationKind(String code) {
        if (code == null) return null;
        return switch (code) {
            case "DELIVERY" -> Messages.msg("m.mbrop-delivery");
            case "PAYMENT" -> Messages.msg("m.mbrop-payment");
            case "CREDIT" -> Messages.msg("m.mbrop-credit");
            case "HARVEST" -> Messages.msg("m.mbrop-harvest");
            default -> code;
        };
    }
}
