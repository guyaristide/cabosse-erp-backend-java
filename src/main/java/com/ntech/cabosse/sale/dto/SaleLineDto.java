package com.ntech.cabosse.sale.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Ligne d'écriture d'une vente. */
@Schema(description = "Ligne de vente (article PF + qté + PU négocié)")
public record SaleLineDto(

        @NotNull(message = "{v.article-requis}") UUID articleId,

        @NotNull(message = "{v.quantite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal quantity,

        @NotNull(message = "{v.prix-unitaire-requis}")
        @DecimalMin(value = "0", message = "{v.prix-negatif-interdit}")
        BigDecimal unitPriceFcfa,

        /** Remise ligne 0..100. Optionnel. */
        BigDecimal discountPct,

        @Size(max = 40) String lotRef
) {}
