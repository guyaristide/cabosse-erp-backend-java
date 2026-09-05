package com.ntech.cabosse.producerpayment.controller;

import com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des règlements producteurs et délégués. */
final class ProducerPaymentExportColumns {

    private ProducerPaymentExportColumns() {}

    static List<ExportColumn<ProducerPaymentDtos.PaymentResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reglement"),        ProducerPaymentDtos.PaymentResponseDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-date"),             ProducerPaymentDtos.PaymentResponseDto::date),
                ExportColumn.of(Messages.msg("m.imp-h-beneficiaire"),     ProducerPaymentDtos.PaymentResponseDto::beneficiaryName),
                ExportColumn.of(Messages.msg("m.imp-h-type"),             ProducerPaymentDtos.PaymentResponseDto::beneficiaryKind),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"),   ProducerPaymentDtos.PaymentResponseDto::totalAmount),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-method"), ProducerPaymentDtos.PaymentResponseDto::paymentMethod),
                ExportColumn.of(Messages.msg("m.imp-h-purchase-payment-ref"), ProducerPaymentDtos.PaymentResponseDto::paymentRef),
                ExportColumn.of(Messages.msg("m.imp-h-piece-comptable"),  ProducerPaymentDtos.PaymentResponseDto::pieceRef),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),            ProducerPaymentDtos.PaymentResponseDto::notes));
    }
}
