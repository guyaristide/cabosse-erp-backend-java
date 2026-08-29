package com.ntech.cabosse.commodity.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * État de suivi des pertes / qualité des ventes cacao pour une campagne
 * (backlog NEG-02, reprend l'outil Excel de l'expert).
 */
@Schema(description = "État de suivi des pertes des ventes cacao")
public record CommodityLossReportDto(
        UUID campaignId,
        String campaignLabel,
        int salesCount,
        BigDecimal totalDeclaredKg,
        BigDecimal totalDischargedKg,
        BigDecimal totalAcceptedKg,
        /** Perte totale de poids = déclaré − accepté. */
        BigDecimal totalLossKg,
        /** Taux de réfaction global = (déclaré − accepté) / déclaré × 100. */
        BigDecimal refactionRatePct,
        /** Humidité moyenne (%), simple moyenne des ventes renseignées. */
        BigDecimal avgHumidityPct,
        /** Grainage moyen, simple moyenne des ventes renseignées. */
        BigDecimal avgGrainage,
        BigDecimal totalCommercialFcfa,
        BigDecimal totalInvoicedTtcFcfa,
        BigDecimal totalMarginFcfa
) {}
