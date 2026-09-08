package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportEnumLabels;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;
import java.util.Map;

/** Colonnes de l'export des avances aux délégués collecteurs. */
final class CollectorAdvanceExportColumns {

    private CollectorAdvanceExportColumns() {}

    static List<ExportColumn<CollectorAdvanceResponseDto>> all(Map<Integer, String> campaignLabels) {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-avance"),           CollectorAdvanceResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),          CollectorAdvanceResponseDto::delegateName),
                ExportColumn.of(Messages.msg("m.imp-h-section"),          CollectorAdvanceResponseDto::sectionName),
                // Le libellé saisi à la création de la campagne, l'année
                // seule ne servant que de repli pour une campagne effacée.
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), dto ->
                        dto.campaignYear() == null ? null
                                : campaignLabels.getOrDefault(
                                        dto.campaignYear(), String.valueOf(dto.campaignYear()))),
                ExportColumn.of(Messages.msg("m.imp-h-date"),             CollectorAdvanceResponseDto::advanceDate),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),   CollectorAdvanceResponseDto::advanceAmount),
                ExportColumn.of(Messages.msg("m.imp-h-consomme-amount"),  CollectorAdvanceResponseDto::consumedAmount),
                ExportColumn.of(Messages.msg("m.imp-h-solde-amount"),     CollectorAdvanceResponseDto::remaining),
                ExportColumn.of(Messages.msg("m.imp-h-status"), dto ->
                        ExportEnumLabels.advanceStatus(dto.status())),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-method"), dto ->
                        ExportEnumLabels.paymentMethod(dto.paymentMethod())),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"),  CollectorAdvanceResponseDto::pieceRef));
    }
}
