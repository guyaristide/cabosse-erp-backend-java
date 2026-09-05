package com.ntech.cabosse.commodity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Payload de création / mise à jour d'un contrat de vente cacao (NEG-02). */
@Schema(description = "Contrat de vente cacao")
public record SalesContractUpsertDto(
        @NotNull UUID customerId,
        UUID campaignId,
        @DecimalMin("0.0") BigDecimal marginPerKg,
        @Size(max = 40) String label,
        @DecimalMin("0.0") BigDecimal coopPrimePerKg,
        @DecimalMin("0.0") BigDecimal producerPrimePerKg,
        @DecimalMin("0.0") BigDecimal socialPrimePerKg,
        @Size(max = 500) String notes
) {}
