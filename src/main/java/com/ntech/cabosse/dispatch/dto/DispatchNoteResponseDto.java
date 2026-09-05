package com.ntech.cabosse.dispatch.dto;

import com.ntech.cabosse.dispatch.entity.DispatchNoteEntity;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Un bordereau de sortie, tel que l'écran le lit (CE-195). */
public record DispatchNoteResponseDto(
        UUID id,
        String ref,
        LocalDate date,
        UUID siteId,
        String siteName,
        UUID articleId,
        String articleName,
        String articleUnit,
        UUID customerId,
        String customerName,
        String truckNumber,
        UUID campaignId,
        Integer campaignYear,
        List<DispatchLineView> lines,
        BigDecimal totalGrossKg,
        Integer totalBags,
        BigDecimal totalNetKg,
        DispatchNoteStatus status,
        UUID saleId,
        String saleRef,
        String notes,
        String cancellationReason,
        Instant createdAt
) {
    public static DispatchNoteResponseDto from(DispatchNoteEntity e) {
        return new DispatchNoteResponseDto(
                e.id, e.ref, e.date, e.siteId, e.siteName,
                e.articleId, e.articleName, e.articleUnit,
                e.customerId, e.customerName, e.truckNumber,
                e.campaignId, e.campaignYear,
                e.lines == null ? List.of() : e.lines.stream().map(DispatchLineView::from).toList(),
                e.totalGrossKg, e.totalBags, e.totalNetKg,
                e.status, e.saleId, e.saleRef, e.notes,
                e.cancellationReason, e.createdAt);
    }
}
