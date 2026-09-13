package com.ntech.cabosse.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Un objectif mensuel à poser.
 *
 * <p>Les deux montants sont facultatifs et indépendants : une structure
 * peut se fixer un objectif de collecte sans objectif de vente. Absent,
 * l'objectif n'existe pas et aucun écart n'est calculé, ce qui n'est pas
 * la même chose qu'un objectif à zéro.</p>
 */
@Schema(description = "Objectif mensuel à enregistrer")
public record CampaignTargetUpsertDto(
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "{v.mois-invalide}") String month,
        @PositiveOrZero BigDecimal collectionTargetKg,
        @PositiveOrZero BigDecimal saleTargetKg
) {
}
