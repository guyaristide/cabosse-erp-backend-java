package com.ntech.cabosse.dispatch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * L'appel d'un reçu, ou du stock de départ, sur le bordereau de sortie
 * (CE-195) : le net prélevé, jamais plus que le disponible de la source,
 * et les sacs emportés.
 */
@Schema(description = "Une ligne du bordereau de sortie : l'appel d'un reçu ou du stock de départ")
public record DispatchLineInputDto(
        /** Reçu appelé. Exclusif de {@link #openingMovementId}. */
        UUID receiptId,
        /**
         * Amorçage appelé, quand la matière vient du stock de départ.
         *
         * <p>Un bordereau ne se composait que de reçus d'achat, et un
         * magasin dont la matière vient du stock de départ ne pouvait
         * rien charger (expert, 14/10/2026).</p>
         */
        UUID openingMovementId,
        @NotNull(message = "{v.poids-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.poids-positif-requis}")
        BigDecimal netKg,
        @Min(value = 0, message = "{v.pesee-sacs-positifs}")
        Integer bagsCount,
        /** Ce que le magasinier note sur la ligne, souvent pour un appel partiel. */
        @Size(max = 300) String comment
) {}
