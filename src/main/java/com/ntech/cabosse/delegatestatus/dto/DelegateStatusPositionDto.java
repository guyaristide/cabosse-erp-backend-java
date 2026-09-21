package com.ntech.cabosse.delegatestatus.dto;

import com.ntech.cabosse.delegatestatus.entity.DelegateStatusPositionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une position prise sur un délégué, avec ce qu'il devait ce jour-là.
 *
 * <p>{@code owedAmount} est le constat figé à la prise de position. Lu à
 * côté du dû du jour, il dit si quelque chose a été recouvré depuis.</p>
 */
public record DelegateStatusPositionDto(
        UUID id,
        UUID delegateSupplierId,
        UUID statusId,
        String statusCode,
        String statusLabel,
        LocalDate effectiveDate,
        String reason,
        BigDecimal owedAmount,
        UUID campaignId,
        String campaignLabel,
        Instant createdAt,
        String createdByEmail) {

    public static DelegateStatusPositionDto from(DelegateStatusPositionEntity e) {
        return new DelegateStatusPositionDto(
                e.id, e.delegateSupplierId, e.statusId, e.statusCode, e.statusLabel,
                e.effectiveDate, e.reason, e.owedAmount, e.campaignId, e.campaignLabel,
                e.createdAt, e.createdByEmail);
    }
}
