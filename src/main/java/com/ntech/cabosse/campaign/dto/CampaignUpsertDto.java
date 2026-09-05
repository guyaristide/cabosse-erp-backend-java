package com.ntech.cabosse.campaign.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload de création / mise à jour d'une campagne.
 *
 * <p>Le code {@code CMP-YYYY-NN} est généré côté serveur (jamais saisi).
 * Le statut n'est pas modifiable via ce payload — la clôture passe par
 * un endpoint dédié.</p>
 *
 * <p>L'année agricole n'est pas saisie non plus : elle se déduit de
 * {@code startDate}. Une saison à cheval sur deux années civiles rendait
 * la saisie ambiguë, et deux campagnes équivalentes se retrouvaient avec
 * des années différentes selon la personne qui les créait.</p>
 */
@Schema(description = "Payload de création / édition d'une campagne membres")
public record CampaignUpsertDto(

        @NotBlank(message = "{v.libelle-requis}")
        @Size(min = 3, max = 120, message = "{v.libelle-entre-3-et-120-caracteres}")
        String label,

        /**
         * Principale ou intermédiaire. Principale par défaut : c'est la
         * campagne qu'on ouvre en premier.
         */
        com.ntech.cabosse.campaign.entity.CampaignKind kind,

        @NotNull(message = "{v.date-d-ouverture-requise}")
        LocalDate startDate,

        LocalDate endDate,

        @NotNull(message = "{v.prix-de-base-requis}")
        @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}") BigDecimal basePricePerKg,

        List<@Valid QualityPremiumPayload> qualityPremiums,

        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}") @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}") BigDecimal ristournePct,

        @Size(max = 80, message = "{v.mode-de-paiement-trop-long}")
        String defaultPaymentMethod,

        @Size(max = 800, message = "{v.notes-trop-longues}")
        String notes

) {

    @Schema(description = "Prime qualité par grade de fèves")
    public record QualityPremiumPayload(
            @NotNull String grade,
            @NotNull @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}") BigDecimal premiumPerKg
    ) {}
}
