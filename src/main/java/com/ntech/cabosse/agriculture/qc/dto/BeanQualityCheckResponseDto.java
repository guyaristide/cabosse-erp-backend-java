package com.ntech.cabosse.agriculture.qc.dto;

import com.ntech.cabosse.agriculture.qc.entity.BeanGrade;
import com.ntech.cabosse.agriculture.qc.entity.BeanQualityCheckEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BeanQualityCheckResponseDto(
        UUID id,
        String ref,
        UUID dryingBatchId,
        String dryingBatchRef,
        Integer cutTestSampleCount,
        BigDecimal wellFermentedPct,
        BigDecimal humidityPct,
        BigDecimal defectsPct,
        BeanGrade grade,
        boolean conformOverall,
        BigDecimal acceptedKg,
        UUID beanArticleId,
        String beanArticleCode,
        String beanArticleName,
        UUID siteId,
        String siteName,
        String lotRef,
        Instant validatedAt,
        String validatedByEmail,
        boolean stockMovementCreated,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static BeanQualityCheckResponseDto from(BeanQualityCheckEntity e) {
        return new BeanQualityCheckResponseDto(
                e.id, e.ref,
                e.dryingBatchId, e.dryingBatchRef,
                e.cutTestSampleCount, e.wellFermentedPct, e.humidityPct, e.defectsPct,
                e.grade, e.conformOverall, e.acceptedKg,
                e.beanArticleId, e.beanArticleCode, e.beanArticleName,
                e.siteId, e.siteName,
                e.lotRef, e.validatedAt, e.validatedByEmail, e.stockMovementCreated,
                e.notes, e.createdAt, e.updatedAt
        );
    }
}
