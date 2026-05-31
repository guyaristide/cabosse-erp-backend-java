package com.ntech.cabosse.achats.dto;

import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderCancellation;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.entity.PurchaseOrderLine;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Bon de commande")
public record PurchaseOrderResponseDto(
        UUID id,
        String ref,
        UUID siteId,
        UUID supplierId,
        String supplierName,
        String supplierLegalName,
        LocalDate orderDate,
        LocalDate deliveryDate,
        LocalDate invoiceDate,
        String invoiceNumber,
        String paymentTerms,
        List<LineDto> lines,
        BigDecimal subtotalHtFcfa,
        BigDecimal transportFcfa,
        BigDecimal vatRatePct,
        BigDecimal vatFcfa,
        BigDecimal totalTtcFcfa,
        List<String> activityCodes,
        boolean hasAttachment,
        /** URL relative à utiliser pour récupérer la facture jointe (authentifié). */
        String attachmentUrl,
        String notes,
        /** Si {@code true}, transport ventilé au CMUP au markDelivered. */
        boolean incorporateFreightInCmup,
        BcStatus status,
        CancellationDto cancellation,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt
) {
    public record LineDto(
            UUID id,
            UUID articleId,
            String articleCode,
            String designation,
            /** Type d'article (RAW_MATERIAL | FINISHED_PRODUCT | CONSUMABLE | PACKAGING | TRANSPORT). */
            String articleType,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPriceFcfa,
            BigDecimal discountPct,
            BigDecimal totalLineFcfa,
            List<String> activityCodes
    ) {
        static LineDto from(PurchaseOrderLine l) {
            return new LineDto(
                    l.id, l.articleId, l.articleCode, l.designation, l.articleType,
                    l.quantity, l.unit, l.unitPriceFcfa, l.discountPct,
                    l.totalLineFcfa, l.activityCodes
            );
        }
    }

    public record CancellationDto(
            String reason,
            String cancelledBy,
            Instant cancelledAt,
            BcStatus previousStatus
    ) {
        static CancellationDto from(PurchaseOrderCancellation c) {
            return new CancellationDto(c.reason, c.cancelledBy, c.cancelledAt, c.previousStatus);
        }
    }

    public static PurchaseOrderResponseDto from(PurchaseOrderEntity e) {
        boolean hasAttachment = e.attachmentFileId != null;
        String attachmentUrl = hasAttachment
                ? "/api/v1/purchase-orders/" + e.id + "/attachment"
                : null;
        return new PurchaseOrderResponseDto(
                e.id, e.ref, e.siteId,
                e.supplierId, e.supplierName, e.supplierLegalName,
                e.orderDate, e.deliveryDate, e.invoiceDate, e.invoiceNumber,
                e.paymentTerms,
                e.lines == null ? List.of() : e.lines.stream().map(LineDto::from).toList(),
                e.subtotalHtFcfa, e.transportFcfa, e.vatRatePct, e.vatFcfa, e.totalTtcFcfa,
                e.activityCodes == null ? List.of() : e.activityCodes,
                hasAttachment, attachmentUrl,
                e.notes,
                e.incorporateFreightInCmup,
                e.status,
                e.cancellation == null ? null : CancellationDto.from(e.cancellation),
                e.createdByEmail, e.createdAt, e.updatedAt
        );
    }
}
