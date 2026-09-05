package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des reçus d'achat producteur. */
final class ProducerPurchaseExportColumns {

    private ProducerPurchaseExportColumns() {}

    static List<ExportColumn<ProducerPurchaseResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-recu"),                 ProducerPurchaseResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date"),                 ProducerPurchaseResponseDto::date),
                ExportColumn.of(Messages.msg("m.imp-h-n-recu-officiel"),     ProducerPurchaseResponseDto::officialReceiptRef),
                ExportColumn.of(Messages.msg("m.imp-h-producteur"),           ProducerPurchaseResponseDto::producerName),
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"),      ProducerPurchaseResponseDto::producerCode),
                ExportColumn.of(Messages.msg("m.imp-h-section"),              ProducerPurchaseResponseDto::sectionName),
                ExportColumn.of(Messages.msg("m.imp-h-member-village"),              ProducerPurchaseResponseDto::village),
                ExportColumn.of(Messages.msg("m.imp-h-article"),              ProducerPurchaseResponseDto::articleName),
                ExportColumn.of(Messages.msg("m.imp-h-nb-sacs"),              ProducerPurchaseResponseDto::nbSacs),
                ExportColumn.of("poids-kg", Messages.msg("m.imp-h-poids-kg"), ColumnKind.NUMBER_QTY,           ProducerPurchaseResponseDto::weightKg),
                ExportColumn.of(Messages.msg("m.imp-h-prix-kg-amount"),       ProducerPurchaseResponseDto::guaranteedPricePerKg),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),       ProducerPurchaseResponseDto::amount),
                ExportColumn.of(Messages.msg("m.imp-h-paye-amount"),          ProducerPurchaseResponseDto::amountPaid),
                ExportColumn.of(Messages.msg("m.imp-h-retenue-credit-amount"), ProducerPurchaseResponseDto::creditImputed),
                ExportColumn.of(Messages.msg("m.imp-h-reste-du-amount"),      ProducerPurchaseResponseDto::remainder),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),              ProducerPurchaseResponseDto::delegateName),
                ExportColumn.of(Messages.msg("m.imp-h-bordereau"),            ProducerPurchaseResponseDto::deliveryRef),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"),             ProducerPurchaseResponseDto::campaignYear),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"),      ProducerPurchaseResponseDto::pieceRef));
    }
}
