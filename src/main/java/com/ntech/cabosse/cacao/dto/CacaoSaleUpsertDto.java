package com.ntech.cabosse.cacao.dto;

import jakarta.validation.Valid;
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
        BigDecimal pricePerKgFcfa,
        /** Surcharges des primes (sinon taux du contrat × poids accepté). */
        BigDecimal coopPrimeFcfa,
        BigDecimal producerPrimeFcfa,
        BigDecimal socialPrimeFcfa,
        /** Surcharge du taux de TVA (sinon préférence tenant). */
        BigDecimal vatRatePct
) {
    public record LogisticsDto(
            @Size(max = 120) String departureLocation,
            @Size(max = 120) String destination,
            @Size(max = 80) String connaissementRef,
            @Size(max = 40) String label,
            @Size(max = 200) String originSections
    ) {}

    public record WeightsDto(
            BigDecimal declaredKg,
            BigDecimal dischargedKg,
            BigDecimal acceptedKg,
            Integer sacsAccepted,
            Integer sacsMissing,
            Integer sacsRejected
    ) {}

    public record RefactionsDto(
            BigDecimal usineKg, BigDecimal humidityKg, BigDecimal foreignMatterKg,
            BigDecimal moldyKg, BigDecimal crabotsKg, BigDecimal brokenKg,
            BigDecimal wasteKg, BigDecimal otherKg
    ) {}

    public record QualityDto(
            BigDecimal grainage, BigDecimal moldyPct, BigDecimal slatePct, BigDecimal purplePct,
            BigDecimal mitedPct, BigDecimal flatPct, BigDecimal germinatedPct, BigDecimal defectivePct,
            BigDecimal foreignMatterPct, BigDecimal ffaPct, BigDecimal brokenPct, BigDecimal humidityPct,
            @Size(max = 40) String taste, @Size(max = 8) String grade, @Size(max = 20) String analysisResult
    ) {}
}
