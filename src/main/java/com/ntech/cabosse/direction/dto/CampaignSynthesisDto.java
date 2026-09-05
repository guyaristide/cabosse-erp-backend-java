package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * La synthèse de pilotage de la vue campagne (épic CE-196, CE-200).
 *
 * <p>Chaque ligne confronte un constat à un objectif de préférence tenant.
 * Le système constate et colore, il ne bloque rien (DEC-25).
 * {@code netMarginRatePct} attend DEC-39 ; le point bas de trésorerie suit
 * la convention recommandée en DEC-40 (solde réel des comptes de
 * trésorerie, historique complet inclus).</p>
 */
public record CampaignSynthesisDto(
        BigDecimal grossMarginRatePct,
        int grossMarginTargetPct,
        BigDecimal netMarginRatePct,
        int netMarginTargetPct,
        BigDecimal treasuryLowPoint,
        String treasuryLowPointMonth,
        BigDecimal maxFinancingNeed,
        BigDecimal residualStockWeight,
        int unsettledDelegatesCount
) {}
