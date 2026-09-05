package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des avances aux délégués collecteurs. */
final class CollectorAdvanceExportColumns {

    private CollectorAdvanceExportColumns() {}

    static List<ExportColumn<CollectorAdvanceResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-avance"),           CollectorAdvanceResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),          CollectorAdvanceResponseDto::delegateName),
                ExportColumn.of(Messages.msg("m.imp-h-section"),          CollectorAdvanceResponseDto::sectionName),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"),         CollectorAdvanceResponseDto::campaignYear),
                ExportColumn.of(Messages.msg("m.imp-h-date"),             CollectorAdvanceResponseDto::advanceDate),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),   CollectorAdvanceResponseDto::advanceAmount),
                ExportColumn.of(Messages.msg("m.imp-h-consomme-amount"),  CollectorAdvanceResponseDto::consumedAmount),
                ExportColumn.of(Messages.msg("m.imp-h-solde-amount"),     CollectorAdvanceResponseDto::remaining),
                ExportColumn.of(Messages.msg("m.imp-h-status"),           CollectorAdvanceResponseDto::status),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-method"), CollectorAdvanceResponseDto::paymentMethod),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"),  CollectorAdvanceResponseDto::pieceRef));
    }
}
