package com.ntech.cabosse.qualitygrade.dto;

import com.ntech.cabosse.qualitygrade.entity.QualityNormEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Seuil de qualité du tenant")
public record QualityNormResponseDto(
        UUID id, String elementCode, String label,
        BigDecimal acceptanceMaxPct, BigDecimal refactionMaxPct,
        int sortOrder, boolean active, Instant createdAt, Instant updatedAt
) {
    public static QualityNormResponseDto from(QualityNormEntity e) {
        return new QualityNormResponseDto(e.id, e.elementCode, e.label,
                e.acceptanceMaxPct, e.refactionMaxPct, e.sortOrder,
                e.active, e.createdAt, e.updatedAt);
    }
}
