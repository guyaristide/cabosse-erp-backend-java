package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des reçus d'achat producteur. */
final class ProducerPurchaseExportColumns {

    private ProducerPurchaseExportColumns() {}

    static List<ExportColumn<ProducerPurchaseResponseDto>> all() {
        return List.of(
                ExportColumn.of("Reçu",                 ProducerPurchaseResponseDto::ref),
                ExportColumn.of("Date",                 ProducerPurchaseResponseDto::date),
                ExportColumn.of("N° reçu officiel",     ProducerPurchaseResponseDto::officialReceiptRef),
                ExportColumn.of("Producteur",           ProducerPurchaseResponseDto::producerName),
                ExportColumn.of("Code producteur",      ProducerPurchaseResponseDto::producerCode),
                ExportColumn.of("Section",              ProducerPurchaseResponseDto::sectionName),
                ExportColumn.of("Village",              ProducerPurchaseResponseDto::village),
                ExportColumn.of("Article",              ProducerPurchaseResponseDto::articleName),
                ExportColumn.of("Nb sacs",              ProducerPurchaseResponseDto::nbSacs),
                ExportColumn.of("Poids (kg)",           ProducerPurchaseResponseDto::weightKg),
                ExportColumn.of("Prix/kg (FCFA)",       ProducerPurchaseResponseDto::guaranteedPricePerKgFcfa),
                ExportColumn.of("Montant (FCFA)",       ProducerPurchaseResponseDto::amountFcfa),
                ExportColumn.of("Payé (FCFA)",          ProducerPurchaseResponseDto::amountPaidFcfa),
                ExportColumn.of("Retenue crédit (FCFA)", ProducerPurchaseResponseDto::creditImputedFcfa),
                ExportColumn.of("Reste dû (FCFA)",      ProducerPurchaseResponseDto::remainderFcfa),
                ExportColumn.of("Délégué",              ProducerPurchaseResponseDto::delegateName),
                ExportColumn.of("Bordereau",            ProducerPurchaseResponseDto::deliveryRef),
                ExportColumn.of("Campagne",             ProducerPurchaseResponseDto::campaignYear),
                ExportColumn.of("Pièce comptable",      ProducerPurchaseResponseDto::pieceRef));
    }
}
