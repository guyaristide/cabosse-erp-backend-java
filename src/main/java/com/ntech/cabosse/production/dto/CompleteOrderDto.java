package com.ntech.cabosse.production.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Saisie de la quantité réellement produite à la complétion")
public record CompleteOrderDto(

        @NotNull(message = "Quantité produite requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal producedQty,

        @Size(max = 500) String notes
) {}
