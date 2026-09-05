package com.ntech.cabosse.commodity.dto;

import com.ntech.cabosse.commodity.entity.CommoditySalePayment;
import com.ntech.cabosse.reception.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Un encaissement client, tel que l'écran le lit (CE-194). */
public record CommoditySalePaymentView(
        UUID id,
        LocalDate paidOn,
        BigDecimal amount,
        PaymentMethod method,
        UUID bankAccountId,
        String paymentRef,
        String pieceRef,
        String recordedByEmail,
        Instant recordedAt,
        String notes
) {
    public static CommoditySalePaymentView from(CommoditySalePayment p) {
        return new CommoditySalePaymentView(p.id, p.paidOn, p.amount, p.method,
                p.bankAccountId, p.paymentRef, p.pieceRef, p.recordedByEmail,
                p.recordedAt, p.notes);
    }
}
