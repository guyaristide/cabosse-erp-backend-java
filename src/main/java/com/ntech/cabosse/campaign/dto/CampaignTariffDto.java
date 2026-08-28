package com.ntech.cabosse.campaign.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Nouveau barème d'une campagne.
 *
 * <p>Le motif est obligatoire : c'est lui qui rend la décision
 * contestable plus tard. Un historique disant qu'un prix est passé de 900
 * à 950 sans dire pourquoi ne vaudrait guère mieux qu'une absence
 * d'historique.</p>
 */
@Schema(description = "Nouveau barème d'une campagne")
public record CampaignTariffDto(

        @NotNull(message = "{v.prix-de-base-requis}")
        @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}")
        BigDecimal basePricePerKgFcfa,

        @Valid List<CampaignUpsertDto.QualityPremiumPayload> qualityPremiums,

        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}")
        @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}")
        BigDecimal ristournePct,

        @NotBlank(message = "{v.motif-requis}")
        @Size(min = 5, max = 500, message = "{v.motif-entre-5-et-500-caracteres}")
        String reason
) {}
