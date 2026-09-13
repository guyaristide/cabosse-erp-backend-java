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
        /** Le prix payé au producteur, rapporté au kilo. */
        BigDecimal avgPurchasePricePerKg,
        /**
         * Le coût d'achat complet au kilo : prix payé au producteur plus
         * les frais que la structure a désignés dans ses paramètres.
         *
         * <p>Absent tant qu'aucun compte de frais n'est déclaré : un coût
         * « complet » égal au prix nu se lirait comme une absence de
         * frais, ce qui serait faux (coopérative, 13/09/2026).</p>
         */
        BigDecimal avgPurchaseCostPerKg,
        /** Les frais retenus sur la campagne, pour expliquer l'écart. */
        BigDecimal purchaseSideCosts,
        BigDecimal avgSalePricePerKg,
        BigDecimal avgUnitMargin
) {}
