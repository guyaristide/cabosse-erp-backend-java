package com.ntech.cabosse.agriculture.qc.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record BeanQualityCheckUpsertDto(
        @NotNull UUID dryingBatchId,
        Integer cutTestSampleCount,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal wellFermentedPct,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal humidityPct,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal defectsPct,
        String grade,
        boolean conformOverall,
        @DecimalMin("0.0") BigDecimal acceptedKg,
        UUID beanArticleId,
        UUID siteId,
        @Size(max = 500) String notes
) {}
