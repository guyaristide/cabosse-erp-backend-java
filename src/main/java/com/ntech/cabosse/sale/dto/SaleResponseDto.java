package com.ntech.cabosse.sale.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleCancellation;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleLine;
import com.ntech.cabosse.sale.entity.SalePayment;
import com.ntech.cabosse.sale.entity.SaleStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Vente")
public record SaleResponseDto(
        UUID id,
        String ref,
        UUID siteId,
        String siteName,
        SaleChannel channel,

        UUID customerId,
        String customerCode,
        String customerName,
        String customerLegalName,
        String channelTypeSnapshot,

        LocalDate saleDate,
        LocalDate dueDate,
        LocalDate deliveryDate,

        List<LineView> lines,

        BigDecimal subtotalHt,
        BigDecimal discountPct,
        BigDecimal discount,
        BigDecimal vatRatePct,
        BigDecimal vat,
        BigDecimal totalTtc,
        BigDecimal totalCost,
        BigDecimal grossMargin,

        List<PaymentView> payments,
        BigDecimal totalPaid,
        BigDecimal balanceDue,
        PaymentStatus paymentStatus,

        SaleStatus status,
        CancellationView cancellation,

        String invoiceNumber,
        String notes,

        Instant createdAt,
        Instant updatedAt,
        String createdByEmail
) {

    public record LineView(
            UUID id, UUID articleId,
            String articleCode, String articleName, String articleUnit,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal standardPrice,
            BigDecimal discountPct, BigDecimal lineTotalHt,
            BigDecimal cmupAtSale, BigDecimal lineMargin, String lotRef
    ) {}

    public record PaymentView(
            UUID id, LocalDate paidOn, BigDecimal amount,
            PaymentMethod method, String paymentNoteRef, String notes,
            String recordedByEmail, Instant recordedAt
    ) {}

    public record CancellationView(
            String reason, String cancelledByEmail, Instant cancelledAt, SaleStatus previousStatus
    ) {}

    public static SaleResponseDto from(SaleEntity e) {
        BigDecimal totalTtc = e.totalTtc == null ? BigDecimal.ZERO : e.totalTtc;
        BigDecimal totalPaid = e.totalPaid == null ? BigDecimal.ZERO : e.totalPaid;
        BigDecimal balanceDue = totalTtc.subtract(totalPaid);
        return new SaleResponseDto(
                e.id, e.ref, e.siteId, e.siteName, e.channel,
                e.customerId, e.customerCode, e.customerName, e.customerLegalName,
                e.channelTypeSnapshot,
                e.saleDate, e.dueDate, e.deliveryDate,
                e.lines == null ? List.of() : e.lines.stream().map(SaleResponseDto::lineView).toList(),
                e.subtotalHt, e.discountPct, e.discount, e.vatRatePct, e.vat,
                e.totalTtc, e.totalCost, e.grossMargin,
                e.payments == null ? List.of() : e.payments.stream().map(SaleResponseDto::paymentView).toList(),
                e.totalPaid, balanceDue, e.paymentStatus,
                e.status,
                e.cancellation == null ? null : cancellationView(e.cancellation),
                e.invoiceNumber, e.notes,
                e.createdAt, e.updatedAt, e.createdByEmail
        );
    }

    private static LineView lineView(SaleLine l) {
        return new LineView(
                l.id, l.articleId, l.articleCode, l.articleName, l.articleUnit,
                l.quantity, l.unitPrice, l.standardPrice,
                l.discountPct, l.lineTotalHt,
                l.cmupAtSale, l.lineMargin, l.lotRef
        );
    }

    private static PaymentView paymentView(SalePayment p) {
        return new PaymentView(
                p.id, p.paidOn, p.amount, p.method, p.paymentNoteRef, p.notes,
                p.recordedByEmail, p.recordedAt
        );
    }

    private static CancellationView cancellationView(SaleCancellation c) {
        return new CancellationView(c.reason, c.cancelledByEmail, c.cancelledAt, c.previousStatus);
    }
}
