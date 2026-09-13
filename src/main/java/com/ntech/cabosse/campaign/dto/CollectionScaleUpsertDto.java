package com.ntech.cabosse.campaign.dto;

import jakarta.validation.constraints.PositiveOrZero;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Le barème de collecte à enregistrer, composante par composante.
 *
 * <p>Les trois sont facultatives et indépendantes : une structure peut
 * connaître ce que le barème accorde au transport sans connaître le
 * reste. Le total se somme, il ne se saisit pas.</p>
 */
@Schema(description = "Barème de collecte du conseil de filière, au kilo")
public record CollectionScaleUpsertDto(
        @PositiveOrZero BigDecimal transportPerKg,
        @PositiveOrZero BigDecimal gatheringPerKg,
        @PositiveOrZero BigDecimal buyerRemunerationPerKg
) {
}
