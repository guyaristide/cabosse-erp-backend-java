package com.ntech.cabosse.agriculture.harvest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record HarvestUpsertDto(
        @NotNull UUID parcelId,
        UUID memberId,
        @Min(2000) @Max(2100) int campaignYear,
        @NotNull LocalDate harvestDate,
        @DecimalMin("0.0") BigDecimal cabossesKg,
        @DecimalMin("0.0") BigDecimal freshBeansKg,
        @Size(max = 500) String qualityNotes,
        @Size(max = 500) String notes
) {}
