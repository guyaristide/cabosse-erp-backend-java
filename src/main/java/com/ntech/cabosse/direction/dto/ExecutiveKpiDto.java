package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * KPI consolidé du tableau de bord exécutif.
 *
 * @param key      clé stable pour l'identification ({@code revenue},
 *                 {@code margin}, {@code cash}, {@code stockValue})
 * @param label    libellé affichable
 * @param current  valeur sur la période courante
 * @param previous valeur sur la période précédente comparable
 * @param unit     unité d'affichage (FCFA au MVP)
 */
public record ExecutiveKpiDto(
        String key,
        String label,
        BigDecimal current,
        BigDecimal previous,
        String unit
) {}
