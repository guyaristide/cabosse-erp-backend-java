package com.ntech.cabosse.purchaserequest.dto;

import com.ntech.cabosse.purchaserequest.entity.PurchaseRequestEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Demande d'achat")
public record PurchaseRequestResponseDto(
        UUID id, String ref, UUID siteId,
        UUID supplierId, String supplierName,
        LocalDate requestDate, String justification,
        List<LineView> lines, BigDecimal estimatedTotalFcfa,
        String status, String decisionReason,
        Instant submittedAt, Instant decidedAt, String decidedByEmail,
        UUID convertedOrderId, String convertedOrderRef,
        Instant createdAt, String createdByEmail
) {
    public record LineView(UUID articleId, String articleCode, String designation,
                           String unit, BigDecimal quantity,
                           BigDecimal estimatedUnitPriceFcfa, BigDecimal estimatedLineFcfa) {}

    public static PurchaseRequestResponseDto from(PurchaseRequestEntity e) {
        List<LineView> lines = e.lines == null ? List.of() : e.lines.stream()
                .map(l -> new LineView(l.articleId, l.articleCode, l.designation, l.unit,
                        l.quantity, l.estimatedUnitPriceFcfa, l.estimatedLineFcfa))
                .toList();
        return new PurchaseRequestResponseDto(
                e.id, e.ref, e.siteId, e.supplierId, e.supplierName,
                e.requestDate, e.justification, lines, e.estimatedTotalFcfa,
                e.status != null ? e.status.name() : null, e.decisionReason,
                e.submittedAt, e.decidedAt, e.decidedByEmail,
                e.convertedOrderId, e.convertedOrderRef,
                e.createdAt, e.createdByEmail);
    }
}
