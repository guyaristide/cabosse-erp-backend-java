package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Vue lecture d'une ligne du journal des mouvements. */
@Schema(description = "Mouvement de stock (journal)")
public record StockMovementResponseDto(
        UUID id,
        String ref,
        UUID articleId,
        UUID siteId,
        String articleCode,
        String articleName,
        String articleUnit,
        String siteName,
        MovementKind kind,
        BigDecimal quantitySigned,
        BigDecimal unitPrice,
        BigDecimal total,
        BigDecimal quantityAfter,
        BigDecimal cmupAfter,
        MovementSource sourceType,
        String sourceRef,
        UUID sourceEntityId,
        UUID transferId,
        String reason,
        String lotRef,
        String notes,
        String actorEmail,
        Instant occurredAt,
        Instant createdAt
) {
    public static StockMovementResponseDto from(StockMovementEntity e) {
        return new StockMovementResponseDto(
                e.id, e.ref,
                e.articleId, e.siteId,
                e.articleCode, e.articleName, e.articleUnit, e.siteName,
                e.kind, e.quantitySigned, e.unitPrice, e.total,
                e.quantityAfter, e.cmupAfter,
                e.sourceType, e.sourceRef, e.sourceEntityId, e.transferId,
                e.reason, e.lotRef, e.notes, e.actorEmail,
                e.occurredAt, e.createdAt
        );
    }
}
