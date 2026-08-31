package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Avance à un délégué collecteur")
public record CollectorAdvanceResponseDto(
        UUID id, String ref,
        UUID delegateSupplierId, String delegateName,
        UUID sectionId, String sectionName,
        Integer campaignYear, UUID siteId,
        LocalDate advanceDate, BigDecimal advanceAmountFcfa, String paymentMethod,
        BigDecimal consumedAmountFcfa, BigDecimal remainingFcfa,
        String status, String pieceRef, String notes,
        /** Ce que le décaissement a réellement mouvementé : compte, référence, frais. */
        UUID bankAccountId, String paymentRef, BigDecimal bankFeesFcfa,
        List<DeliveryView> deliveries,
        Instant closedAt, Instant createdAt, String createdByEmail,
        /** Qui a approuvé, refusé ou décaissé, et quand. Vide tant que le
            geste n'a pas eu lieu. */
        Instant approvedAt, String approvedByEmail,
        String rejectionReason, Instant rejectedAt, String rejectedByEmail,
        Instant disbursedAt, String disbursedByEmail,
        java.util.List<com.ntech.cabosse.shared.storage.AttachmentDto> attachments
) {
    public record DeliveryView(UUID id, LocalDate date, UUID articleId, String articleCode,
                               String articleName, String articleUnit, BigDecimal quantity,
                               BigDecimal unitPriceFcfa, BigDecimal amountFcfa, String pieceRef) {}

    public static CollectorAdvanceResponseDto from(CollectorAdvanceEntity e) {
        List<DeliveryView> deliveries = e.deliveries == null ? List.of() : e.deliveries.stream()
                .map(d -> new DeliveryView(d.id, d.date, d.articleId, d.articleCode, d.articleName,
                        d.articleUnit, d.quantity, d.unitPriceFcfa, d.amountFcfa, d.pieceRef))
                .toList();
        return new CollectorAdvanceResponseDto(
                e.id, e.ref, e.delegateSupplierId, e.delegateName, e.sectionId, e.sectionName,
                e.campaignYear, e.siteId, e.advanceDate, e.advanceAmountFcfa,
                e.paymentMethod != null ? e.paymentMethod.name() : null,
                e.consumedAmountFcfa, e.remainingFcfa,
                e.status != null ? e.status.name() : null, e.pieceRef, e.notes,
                e.bankAccountId, e.paymentRef, e.bankFeesFcfa,
                deliveries, e.closedAt, e.createdAt, e.createdByEmail,
                e.approvedAt, e.approvedByEmail,
                e.rejectionReason, e.rejectedAt, e.rejectedByEmail,
                e.disbursedAt, e.disbursedByEmail,
                com.ntech.cabosse.shared.storage.AttachmentDto.fromAll(e.attachments));
    }
}
