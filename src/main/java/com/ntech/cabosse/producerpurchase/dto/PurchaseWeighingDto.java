package com.ntech.cabosse.producerpurchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Une pesée saisie sur le reçu (CE-183).
 *
 * <p>Le net est facultatif : absent, le service le calcule brut moins
 * décote, la décote valant par défaut le nombre de sacs (un kilo de
 * tare par sac, DEC-34). Fourni, il fait foi, c'est la bascule.</p>
 */
@Schema(description = "Une pesée du bordereau : brut, décote, net")
public record PurchaseWeighingDto(
        @NotNull(message = "{v.pesee-brut-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.pesee-brut-positif}")
        BigDecimal grossKg,

        /** Sacs de la pesée, la colonne « MS » du carnet. */
        @jakarta.validation.constraints.Min(value = 0, message = "{v.pesee-sacs-positifs}")
        Integer bagsCount,

        @DecimalMin(value = "0", message = "{v.pesee-decote-positive}")
        BigDecimal deductionKg,

        @DecimalMin(value = "0", inclusive = false, message = "{v.pesee-net-positif}")
        BigDecimal netKg
) {}
