package com.ntech.cabosse.processing.fermentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload création / mise à jour d'un bac de fermentation. Les
 * mesures (température, brassages) sont gérées séparément via les
 * endpoints dédiés (POST /readings, POST /turnings).
 */
public record FermentationBatchUpsertDto(
        /**
         * Récoltes chargées. <strong>Facultatif</strong> : une structure qui
         * achète sa matière au lieu de la récolter charge un bac sans
         * récolte à lui rattacher.
         */
        List<UUID> harvestIds,
        @DecimalMin("0.0") BigDecimal weightInKg,
        @Size(max = 500) String notes
) {}
