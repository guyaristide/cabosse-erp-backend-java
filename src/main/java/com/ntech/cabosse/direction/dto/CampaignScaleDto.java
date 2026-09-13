package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * Le barème de collecte de la campagne, confronté au réalisé.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Le conseil de filière
 * publie ce qu'il accorde à la chaîne de collecte et le décompose ; la
 * structure veut savoir où elle se situe.</p>
 *
 * <p>Absent tant que le barème n'a pas été saisi : confronter un réalisé
 * à un barème à zéro annoncerait un dépassement qui n'existe pas.</p>
 */
public record CampaignScaleDto(
        BigDecimal transportPerKg,
        BigDecimal gatheringPerKg,
        BigDecimal buyerRemunerationPerKg,
        /** La somme des composantes saisies. */
        BigDecimal scaleTotalPerKg,
        /** Ce que la structure réalise au kilo, pour la même campagne. */
        BigDecimal actualPerKg,
        /** Positif, la structure fait mieux que le barème. */
        BigDecimal gapPerKg
) {}
