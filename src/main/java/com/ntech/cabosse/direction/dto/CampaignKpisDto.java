package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * Les indicateurs de tête de la vue campagne (épic CE-196, CE-197).
 *
 * <p>Poids dans l'unité d'article de la campagne, montants dans la devise
 * du tenant : le DTO ne porte que des nombres, les libellés vivent côté
 * client. {@code netResult} reste null tant que DEC-39 n'a pas tranché la
 * lecture du résultat net (exercice comptable ou période de campagne).</p>
 */
public record CampaignKpisDto(
        BigDecimal purchasedWeight,
        BigDecimal soldWeight,
        BigDecimal stockWeight,
        BigDecimal revenue,
        BigDecimal grossMargin,
        BigDecimal netResult,
        BigDecimal advancesDisbursed,
        BigDecimal advancesOutstanding,
        BigDecimal advanceCoverageRatePct,
        BigDecimal avgPurchasePricePerKg,
        BigDecimal avgSalePricePerKg,
        BigDecimal avgUnitMargin
) {}
