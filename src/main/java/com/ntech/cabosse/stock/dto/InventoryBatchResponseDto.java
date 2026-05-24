package com.ntech.cabosse.stock.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un inventaire physique")
public record InventoryBatchResponseDto(
        int adjustedCount,
        int noChangeCount,
        int rejectedCount,
        List<Adjustment> adjustments,
        List<Rejection> rejected
) {
    public record Adjustment(UUID articleId,
                                BigDecimal theoretical,
                                BigDecimal counted,
                                BigDecimal delta,
                                StockItemResponseDto after) {}
    public record Rejection(UUID articleId, String reason) {}
}
