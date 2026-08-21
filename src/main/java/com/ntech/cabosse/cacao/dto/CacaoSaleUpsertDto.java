package com.ntech.cabosse.cacao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload de création d'une vente de cacao export (backlog NEG-02). Le prix
 * et les primes sont pré-remplis depuis le contrat (si {@code contractId}),
 * surchargeables ici. Le poids déclaré alimente la sortie de stock ; le poids
 * accepté la facturation.
 */
@Schema(description = "Création d'une vente de cacao export")
public record CacaoSaleUpsertDto(
        @NotNull LocalDate date,
        @NotNull UUID customerId,
        @NotNull UUID articleId,
        @NotNull UUID siteId,
        UUID campaignId,
        @Size(max = 20) String campaignType,
        UUID contractId,

        @Valid LogisticsDto logistics,
        @Valid @NotNull WeightsDto weights,
        @Valid RefactionsDto refactions,
        @Valid QualityDto quality,

        /** Surcharge du prix (sinon prix bord champ campagne + marge du contrat). */
        @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal pricePerKgFcfa,
        /** Surcharges des primes (sinon taux du contrat × poids accepté). */
        @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal coopPrimeFcfa,
        @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal producerPrimeFcfa,
        @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal socialPrimeFcfa,
        /** Surcharge du taux de TVA (sinon préférence tenant). */
        @DecimalMin(value = "0", message = "Pourcentage négatif interdit")
        @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal vatRatePct
) {
    public record LogisticsDto(
            @Size(max = 120) String departureLocation,
            @Size(max = 120) String destination,
            @Size(max = 80) String connaissementRef,
            @Size(max = 40) String label,
            @Size(max = 200) String originSections
    ) {}

    public record WeightsDto(
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal declaredKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal dischargedKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal acceptedKg,
            Integer sacsAccepted,
            Integer sacsMissing,
            Integer sacsRejected
    ) {}

    public record RefactionsDto(
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal usineKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal humidityKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal foreignMatterKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal moldyKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal crabotsKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal brokenKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal wasteKg,
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal otherKg
    ) {}

    public record QualityDto(
            @DecimalMin(value = "0", message = "Valeur négative interdite") BigDecimal grainage,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal moldyPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal slatePct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal purplePct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal mitedPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal flatPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal germinatedPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal defectivePct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal foreignMatterPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal ffaPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal brokenPct,
            @DecimalMin(value = "0", message = "Pourcentage négatif interdit") @DecimalMax(value = "100", message = "Pourcentage supérieur à 100") BigDecimal humidityPct,
            @Size(max = 40) String taste, @Size(max = 8) String grade, @Size(max = 20) String analysisResult
    ) {}
}
