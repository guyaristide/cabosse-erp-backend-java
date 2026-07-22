package com.ntech.cabosse.cacao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import d'une vente cacao (parsing client), backlog NEG-02. Tout en
 * texte, validé/normalisé côté service. {@code customerName} rapproche le
 * client par nom ; {@code siteId}/{@code campaignId}/{@code campaignType}
 * fixés à l'import. {@code montantFacture} donne le prix (montant ÷ accepté).
 */
@Schema(description = "Ligne d'import vente cacao")
public record CacaoSaleImportRowDto(
        int rowNumber,
        String customerName,
        String productCode,
        String date,
        String siteId,
        String campaignId,
        String campaignType,
        String departureLocation,
        String destination,
        String connaissementRef,
        String label,
        String originSections,
        String declaredKg,
        String dischargedKg,
        String acceptedKg,
        String sacsAccepted,
        String sacsMissing,
        String sacsRejected,
        String usineKg,
        String humidityKg,
        String foreignMatterKg,
        String moldyKg,
        String crabotsKg,
        String brokenKg,
        String wasteKg,
        String otherKg,
        String grainage,
        String moldyPct,
        String slatePct,
        String purplePct,
        String mitedPct,
        String flatPct,
        String germinatedPct,
        String defectivePct,
        String foreignMatterPct,
        String ffaPct,
        String brokenPct,
        String humidityPct,
        String taste,
        String grade,
        String analysisResult,
        String montantFacture
) {}
