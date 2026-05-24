package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue lecture du stock d'un article sur un site.
 */
@Schema(description = "Position stock d'un article sur un site")
public record StockItemResponseDto(
        UUID id,
        UUID articleId,
        UUID siteId,
        String articleCode,
        String articleName,
        String articleUnit,
        ArticleType articleType,
        BigDecimal quantity,
        BigDecimal cmupFcfa,
        BigDecimal alertThreshold,
        BigDecimal totalValueFcfa,
        Instant lastMovementAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static StockItemResponseDto from(StockItemEntity e) {
        BigDecimal value = (e.quantity != null && e.cmupFcfa != null)
                ? e.quantity.multiply(e.cmupFcfa)
                : null;
        return new StockItemResponseDto(
                e.id, e.articleId, e.siteId,
                e.articleCode, e.articleName, e.articleUnit, e.articleType,
                e.quantity, e.cmupFcfa, e.alertThreshold, value,
                e.lastMovementAt, e.createdAt, e.updatedAt
        );
    }
}
