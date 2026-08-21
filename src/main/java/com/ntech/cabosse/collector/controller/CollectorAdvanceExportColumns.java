package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des avances aux délégués collecteurs. */
final class CollectorAdvanceExportColumns {

    private CollectorAdvanceExportColumns() {}

    static List<ExportColumn<CollectorAdvanceResponseDto>> all() {
        return List.of(
                ExportColumn.of("Avance",           CollectorAdvanceResponseDto::ref),
                ExportColumn.of("Délégué",          CollectorAdvanceResponseDto::delegateName),
                ExportColumn.of("Section",          CollectorAdvanceResponseDto::sectionName),
                ExportColumn.of("Campagne",         CollectorAdvanceResponseDto::campaignYear),
                ExportColumn.of("Date",             CollectorAdvanceResponseDto::advanceDate),
                ExportColumn.of("Montant (FCFA)",   CollectorAdvanceResponseDto::advanceAmountFcfa),
                ExportColumn.of("Consommé (FCFA)",  CollectorAdvanceResponseDto::consumedAmountFcfa),
                ExportColumn.of("Solde (FCFA)",     CollectorAdvanceResponseDto::remainingFcfa),
                ExportColumn.of("Statut",           CollectorAdvanceResponseDto::status),
                ExportColumn.of("Mode de paiement", CollectorAdvanceResponseDto::paymentMethod),
                ExportColumn.of("Pièce comptable",  CollectorAdvanceResponseDto::pieceRef));
    }
}
