package com.ntech.cabosse.stock.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un amorçage batch")
public record OpeningBatchResponseDto(
        int createdCount,
        int rejectedCount,
        List<StockItemResponseDto> created,
        List<Rejection> rejected
) {
    public record Rejection(UUID articleId, String reason) {}
}
