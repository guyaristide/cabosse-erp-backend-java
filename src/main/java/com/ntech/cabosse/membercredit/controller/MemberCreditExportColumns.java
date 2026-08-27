package com.ntech.cabosse.membercredit.controller;

import com.ntech.cabosse.membercredit.dto.MemberCreditResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des crédits et avances aux producteurs. */
final class MemberCreditExportColumns {

    private MemberCreditExportColumns() {}

    static List<ExportColumn<MemberCreditResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),        MemberCreditResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-type"),             MemberCreditResponseDto::kind),
                ExportColumn.of(Messages.msg("m.imp-h-producteur"),       MemberCreditResponseDto::memberName),
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),  MemberCreditResponseDto::memberCode),
                ExportColumn.of(Messages.msg("m.imp-h-section"),          MemberCreditResponseDto::sectionName),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"),         MemberCreditResponseDto::campaignLabel),
                ExportColumn.of(Messages.msg("m.imp-h-objet"),            MemberCreditResponseDto::purpose),
                ExportColumn.of(Messages.msg("m.imp-h-montant-fcfa"),   MemberCreditResponseDto::amountFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-demande-le"),       MemberCreditResponseDto::requestedAt),
                ExportColumn.of(Messages.msg("m.imp-h-status"),           MemberCreditResponseDto::status),
                ExportColumn.of(Messages.msg("m.imp-h-decaisse-le"),      MemberCreditResponseDto::disbursedAt),
                ExportColumn.of(Messages.msg("m.imp-h-rembourse-fcfa"), MemberCreditResponseDto::imputedAmountFcfa),
                ExportColumn.of(Messages.msg("m.imp-h-reste-du-fcfa"),  MemberCreditResponseDto::remainingFcfa));
    }
}
