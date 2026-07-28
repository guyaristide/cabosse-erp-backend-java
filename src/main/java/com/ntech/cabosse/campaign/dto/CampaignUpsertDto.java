package com.ntech.cabosse.campaign.dto;

import com.ntech.cabosse.agriculture.qc.entity.BeanGrade;
import jakarta.validation.Valid;
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

        @NotBlank(message = "Libellé requis")
        @Size(min = 3, max = 120, message = "Libellé entre 3 et 120 caractères")
        String label,

        @NotNull(message = "Date d'ouverture requise")
        LocalDate startDate,

        LocalDate endDate,

        @NotNull(message = "Prix de base requis")
        BigDecimal basePricePerKgFcfa,

        List<@Valid QualityPremiumPayload> qualityPremiums,

        BigDecimal ristournePct,

        @Size(max = 80, message = "Mode de paiement trop long")
        String defaultPaymentMethod,

        @Size(max = 800, message = "Notes trop longues")
        String notes

) {

    @Schema(description = "Prime qualité par grade de fèves")
    public record QualityPremiumPayload(
            @NotNull BeanGrade grade,
            @NotNull BigDecimal premiumPerKg
    ) {}
}
