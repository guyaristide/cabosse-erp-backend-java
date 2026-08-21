package com.ntech.cabosse.producerpayment.controller;

import com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

/** Colonnes de l'export des règlements producteurs et délégués. */
final class ProducerPaymentExportColumns {

    private ProducerPaymentExportColumns() {}

    static List<ExportColumn<ProducerPaymentDtos.PaymentResponseDto>> all() {
        return List.of(
                ExportColumn.of("Règlement",        ProducerPaymentDtos.PaymentResponseDto::ref),
                ExportColumn.of("Date",             ProducerPaymentDtos.PaymentResponseDto::date),
                ExportColumn.of("Bénéficiaire",     ProducerPaymentDtos.PaymentResponseDto::beneficiaryName),
                ExportColumn.of("Type",             ProducerPaymentDtos.PaymentResponseDto::beneficiaryKind),
                ExportColumn.of("Montant (FCFA)",   ProducerPaymentDtos.PaymentResponseDto::totalAmountFcfa),
                ExportColumn.of("Mode de paiement", ProducerPaymentDtos.PaymentResponseDto::paymentMethod),
                ExportColumn.of("Référence paiement", ProducerPaymentDtos.PaymentResponseDto::paymentRef),
                ExportColumn.of("Pièce comptable",  ProducerPaymentDtos.PaymentResponseDto::pieceRef),
                ExportColumn.of("Notes",            ProducerPaymentDtos.PaymentResponseDto::notes));
    }
}
