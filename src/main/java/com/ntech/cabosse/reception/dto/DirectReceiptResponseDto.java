package com.ntech.cabosse.reception.dto;

import com.ntech.cabosse.reception.entity.DirectReceiptCancellation;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.entity.DirectReceiptPayment;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Session de réception directe")
public record DirectReceiptResponseDto(
        UUID id,
        String ref,
        UUID siteId,
        LocalDate receivedDate,
        String receiverEmail,
        UUID articleId,
        String articleCode,
        String articleName,
        String articleUnit,
        String deliveryNoteRef,
        List<LineDto> lines,
        BigDecimal subtotalHtFcfa,
        BigDecimal totalPaidFcfa,
        DirectReceiptStatus status,
        CancellationDto cancellation,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {

    public record LineDto(
            UUID id,
            UUID supplierId,
            String supplierCode,
            String supplierName,
            BigDecimal quantity,
            BigDecimal unitPriceFcfa,
            BigDecimal totalLineFcfa,
            String deliveryNoteRef,
            PaymentDto payment,
            String notes
    ) {
        static LineDto from(DirectReceiptLine l) {
            return new LineDto(
                    l.id, l.supplierId, l.supplierCode, l.supplierName,
                    l.quantity, l.unitPriceFcfa, l.totalLineFcfa,
                    l.deliveryNoteRef,
                    l.payment == null ? null : PaymentDto.from(l.payment),
                    l.notes
            );
        }
    }

    public record PaymentDto(
            LocalDate paidOn,
            BigDecimal amountFcfa,
            String paymentNoteRef,
            PaymentMethod method,
            String recordedByEmail,
            Instant recordedAt,
            String notes
    ) {
        static PaymentDto from(DirectReceiptPayment p) {
            return new PaymentDto(
                    p.paidOn, p.amountFcfa, p.paymentNoteRef, p.method,
                    p.recordedByEmail, p.recordedAt, p.notes
            );
        }
    }

    public record CancellationDto(
            String reason,
            String cancelledByEmail,
            Instant cancelledAt,
            DirectReceiptStatus previousStatus
    ) {
        static CancellationDto from(DirectReceiptCancellation c) {
            return new CancellationDto(c.reason, c.cancelledByEmail, c.cancelledAt, c.previousStatus);
        }
    }

    public static DirectReceiptResponseDto from(DirectReceiptEntity e) {
        return new DirectReceiptResponseDto(
                e.id, e.ref, e.siteId, e.receivedDate, e.receiverEmail,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.deliveryNoteRef,
                e.lines == null ? List.of() : e.lines.stream().map(LineDto::from).toList(),
                e.subtotalHtFcfa, e.totalPaidFcfa,
                e.status,
                e.cancellation == null ? null : CancellationDto.from(e.cancellation),
                e.notes,
                e.createdAt, e.updatedAt
        );
    }
}
