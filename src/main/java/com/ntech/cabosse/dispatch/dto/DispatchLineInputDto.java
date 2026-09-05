package com.ntech.cabosse.dispatch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * L'appel d'un reçu sur le bordereau de sortie (CE-195) : le net prélevé,
 * jamais plus que le disponible du reçu, et les sacs emportés.
 */
@Schema(description = "Une ligne du bordereau de sortie : l'appel d'un reçu")
public record DispatchLineInputDto(
        @NotNull(message = "{v.recu-requis}") UUID receiptId,
        @NotNull(message = "{v.poids-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.poids-positif-requis}")
        BigDecimal netKg,
        @Min(value = 0, message = "{v.pesee-sacs-positifs}")
        Integer bagsCount
) {}
