package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.stock.entity.InventorySessionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vue complète d'une session d'inventaire, écarts calculés par ligne. */
public record InventorySessionResponseDto(
        UUID id,
        String ref,
        UUID siteId,
        String siteName,
        String status,
        String reason,
        List<LineView> lines,
        Instant openedAt,
        Instant submittedAt,
        Instant validatedAt,
        Instant cancelledAt,
        String pieceRef,
        /** Somme signée des écarts valorisés au CMUP figé (lignes comptées). */
        BigDecimal totalDeltaValueFcfa
) {
    public record LineView(
            UUID articleId,
            String articleCode,
            String articleName,
            String articleUnit,
            String articleType,
            BigDecimal theoreticalQty,
            BigDecimal cmupFcfa,
            BigDecimal countedQty,
            /** {@code countedQty - theoreticalQty}, {@code null} si non comptée. */
            BigDecimal deltaQty,
            /** Écart valorisé au CMUP figé, {@code null} si non comptée. */
            BigDecimal deltaValueFcfa,
            /** Écart au-delà des seuils d'alerte du tenant. {@code null} si non évalué. */
            Boolean significant,
            String notes
    ) {}

    public static InventorySessionResponseDto from(InventorySessionEntity e) {
        return from(e, null, null);
    }

    /** Variante avec seuils tenant : renseigne {@code significant} par ligne. */
    public static InventorySessionResponseDto from(InventorySessionEntity e,
                                                   BigDecimal thresholdPct,
                                                   BigDecimal thresholdFcfa) {
        BigDecimal totalDelta = BigDecimal.ZERO;
        List<LineView> lines = e.lines == null ? List.of() : e.lines.stream().map(l -> {
            BigDecimal deltaQty = null;
            BigDecimal deltaValue = null;
            if (l.countedQty != null) {
                BigDecimal theoretical = l.theoreticalQty == null ? BigDecimal.ZERO : l.theoreticalQty;
                deltaQty = l.countedQty.subtract(theoretical);
                BigDecimal cmup = l.cmupFcfa == null ? BigDecimal.ZERO : l.cmupFcfa;
                deltaValue = deltaQty.multiply(cmup);
            }
            Boolean significant = (thresholdPct == null && thresholdFcfa == null)
                    ? null
                    : com.ntech.cabosse.stock.service.InventorySessionService
                            .isSignificant(l, thresholdPct, thresholdFcfa);
            return new LineView(
                    l.articleId, l.articleCode, l.articleName, l.articleUnit, l.articleType,
                    l.theoreticalQty, l.cmupFcfa, l.countedQty, deltaQty, deltaValue,
                    significant, l.notes
            );
        }).toList();
        for (LineView l : lines) {
            if (l.deltaValueFcfa() != null) totalDelta = totalDelta.add(l.deltaValueFcfa());
        }
        return new InventorySessionResponseDto(
                e.id, e.ref, e.siteId, e.siteName, e.status, e.reason,
                lines, e.openedAt, e.submittedAt, e.validatedAt, e.cancelledAt,
                e.pieceRef, totalDelta
        );
    }
}
