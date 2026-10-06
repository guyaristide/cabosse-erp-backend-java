package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.stock.entity.StockCorrectionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Une correction de stock telle qu'elle se lit. */
public record StockCorrectionDto(
        UUID id,
        String ref,
        LocalDate date,
        UUID siteId,
        String siteName,
        UUID articleId,
        String articleName,
        String articleUnit,
        String reason,
        Integer bags,
        BigDecimal weightKg,
        BigDecimal unitPrice,
        BigDecimal value,
        String notes,
        String pieceRef,
        String createdBy,
        Instant createdAt
) {
    public static StockCorrectionDto from(StockCorrectionEntity e) {
        return new StockCorrectionDto(
                e.id, e.ref, e.date, e.siteId, e.siteName,
                e.articleId, e.articleName, e.articleUnit,
                e.reason, e.bags, e.weightKg, e.unitPrice, e.value,
                e.notes, e.pieceRef, e.createdBy, e.createdAt);
    }
}
