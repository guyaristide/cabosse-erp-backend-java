package com.ntech.cabosse.agriculture.harvest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload de récolte. La campagne se choisit dans le référentiel
 * ({@code campaignId}) ; à défaut, la campagne ouverte est retenue.
 * L'année n'est pas saisie : elle se déduit de la campagne.
 */
public record HarvestUpsertDto(
        @NotNull UUID parcelId,
        UUID memberId,
        UUID campaignId,
        @NotNull LocalDate harvestDate,
        @DecimalMin("0.0") BigDecimal cabossesKg,
        @DecimalMin("0.0") BigDecimal freshBeansKg,
        @Size(max = 500) String qualityNotes,
        @Size(max = 500) String notes
) {}
