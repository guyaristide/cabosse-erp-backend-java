package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * Un mois de la campagne, pour les courbes de la vue campagne (CE-198) :
 * CA et marge des ventes négoce, tonnages entrés et sortis, solde de
 * trésorerie en fin de mois (borné à aujourd'hui pour le mois courant).
 */
public record CampaignMonthDto(
        String month,
        BigDecimal revenue,
        BigDecimal grossMargin,
        BigDecimal purchasedWeight,
        BigDecimal soldWeight,
        BigDecimal treasuryBalance
) {}
