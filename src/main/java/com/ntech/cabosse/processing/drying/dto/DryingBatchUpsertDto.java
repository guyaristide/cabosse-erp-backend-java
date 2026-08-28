package com.ntech.cabosse.processing.drying.dto;

import com.ntech.cabosse.processing.drying.entity.DryingMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DryingBatchUpsertDto(
        /**
         * Bacs de fermentation à sécher. <strong>Facultatif</strong> : une
         * filière qui ne fermente pas (manioc, maïs, riz, anacarde, fruits,
         * café) sèche de la matière qui n'est passée par aucun bac. L'exiger
         * fermait le module à six filières sur sept.
         */
        List<UUID> fermentationBatchIds,
        @NotNull DryingMethod method,
        @DecimalMin("0.0") BigDecimal weightInKg,
        @Size(max = 500) String notes
) {}
