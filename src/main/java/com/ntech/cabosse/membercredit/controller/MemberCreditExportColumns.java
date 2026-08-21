package com.ntech.cabosse.membercredit.controller;

import com.ntech.cabosse.membercredit.dto.MemberCreditResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des crédits et avances aux producteurs. */
final class MemberCreditExportColumns {

    private MemberCreditExportColumns() {}

    static List<ExportColumn<MemberCreditResponseDto>> all() {
        return List.of(
                ExportColumn.of("Référence",        MemberCreditResponseDto::ref),
                ExportColumn.of("Type",             MemberCreditResponseDto::kind),
                ExportColumn.of("Producteur",       MemberCreditResponseDto::memberName),
                ExportColumn.of("Code producteur",  MemberCreditResponseDto::memberCode),
                ExportColumn.of("Section",          MemberCreditResponseDto::sectionName),
                ExportColumn.of("Campagne",         MemberCreditResponseDto::campaignLabel),
                ExportColumn.of("Objet",            MemberCreditResponseDto::purpose),
                ExportColumn.of("Montant (FCFA)",   MemberCreditResponseDto::amountFcfa),
                ExportColumn.of("Demandé le",       MemberCreditResponseDto::requestedAt),
                ExportColumn.of("Statut",           MemberCreditResponseDto::status),
                ExportColumn.of("Décaissé le",      MemberCreditResponseDto::disbursedAt),
                ExportColumn.of("Remboursé (FCFA)", MemberCreditResponseDto::imputedAmountFcfa),
                ExportColumn.of("Reste dû (FCFA)",  MemberCreditResponseDto::remainingFcfa));
    }
}
