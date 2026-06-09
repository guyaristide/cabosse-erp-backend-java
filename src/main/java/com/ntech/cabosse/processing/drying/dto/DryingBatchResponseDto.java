package com.ntech.cabosse.processing.drying.dto;

import com.ntech.cabosse.processing.drying.entity.DryingBatchEntity;
import com.ntech.cabosse.processing.drying.entity.DryingBatchStatus;
import com.ntech.cabosse.processing.drying.entity.DryingMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DryingBatchResponseDto(
        UUID id,
        String ref,
        List<UUID> fermentationBatchIds,
        List<String> fermentationBatchRefs,
        DryingMethod method,
        DryingBatchStatus status,
        Instant startedAt,
        Instant completedAt,
        Integer durationHours,
        BigDecimal weightInKg,
        BigDecimal weightOutKg,
        BigDecimal finalHumidityPct,
        BigDecimal weightLossPct,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static DryingBatchResponseDto from(DryingBatchEntity e) {
        return new DryingBatchResponseDto(
                e.id, e.ref,
                e.fermentationBatchIds != null ? List.copyOf(e.fermentationBatchIds) : List.of(),
                e.fermentationBatchRefs != null ? List.copyOf(e.fermentationBatchRefs) : List.of(),
                e.method, e.status,
                e.startedAt, e.completedAt, e.durationHours,
                e.weightInKg, e.weightOutKg, e.finalHumidityPct, e.weightLossPct,
                e.notes, e.createdAt, e.updatedAt
        );
    }
}
