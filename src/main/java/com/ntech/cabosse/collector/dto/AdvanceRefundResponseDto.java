package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.collector.entity.AdvanceRefundEntity;
import com.ntech.cabosse.collector.entity.AdvanceRefundStatus;
import com.ntech.cabosse.reception.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un règlement de reliquat d'avance, tel que l'écran le lit (CE-187). */
public record AdvanceRefundResponseDto(
        UUID id,
        String ref,
        UUID delegateSupplierId,
        String delegateName,
        UUID campaignId,
        Integer campaignYear,
        BigDecimal amount,
        /** Montant accordé (« Partiel »), null si accordé tel quel. */
        BigDecimal approvedAmount,
        /** Ce qui sort réellement : l'accordé sinon le demandé. */
        BigDecimal effectiveAmount,
        BigDecimal creditBalanceAtRequest,
        String notes,
        AdvanceRefundStatus status,
        Instant requestedAt,
        String requestedByEmail,
        Instant decidedAt,
        String decidedByEmail,
        String decisionNote,
        Instant paidAt,
        String paidByEmail,
        PaymentMethod paymentMethod,
        UUID bankAccountId,
        String paymentRef,
        BigDecimal bankFees,
        String paymentNote,
        String pieceRef
) {
    public static AdvanceRefundResponseDto from(AdvanceRefundEntity e) {
        return new AdvanceRefundResponseDto(
                e.id, e.ref, e.delegateSupplierId, e.delegateName,
                e.campaignId, e.campaignYear,
                e.amount, e.approvedAmount, e.effectiveAmount(),
                e.creditBalanceAtRequest, e.notes,
                e.status, e.requestedAt, e.requestedByEmail,
                e.decidedAt, e.decidedByEmail, e.decisionNote,
                e.paidAt, e.paidByEmail, e.paymentMethod, e.bankAccountId,
                e.paymentRef, e.bankFees, e.paymentNote, e.pieceRef);
    }
}
