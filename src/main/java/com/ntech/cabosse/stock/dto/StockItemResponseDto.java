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
        /** Quantité sous le seuil d'alerte de l'article. */
        boolean belowThreshold,
        /** Quantité sous le seuil critique (pct du seuil d'alerte, préférence tenant). */
        boolean critical,
        Instant lastMovementAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static StockItemResponseDto from(StockItemEntity e) {
        return from(e, 20);
    }

    /**
     * @param warningPct pourcentage du seuil d'alerte sous lequel le stock
     *                   passe en critique (préférence {@code stockMinWarningPct}).
     */
    public static StockItemResponseDto from(StockItemEntity e, int warningPct) {
        BigDecimal value = (e.quantity != null && e.cmupFcfa != null)
                ? e.quantity.multiply(e.cmupFcfa)
                : null;
        boolean below = e.alertThreshold != null && e.alertThreshold.signum() > 0
                && e.quantity != null && e.quantity.compareTo(e.alertThreshold) < 0;
        boolean crit = below && e.quantity.compareTo(
                e.alertThreshold.multiply(BigDecimal.valueOf(warningPct))
                        .divide(BigDecimal.valueOf(100))) < 0;
        return new StockItemResponseDto(
                e.id, e.articleId, e.siteId,
                e.articleCode, e.articleName, e.articleUnit, e.articleType,
                e.quantity, e.cmupFcfa, e.alertThreshold, value,
                below, crit,
                e.lastMovementAt, e.createdAt, e.updatedAt
        );
    }
}
