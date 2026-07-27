package com.ntech.cabosse.agriculture.potential.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Potentiel de production d'un producteur pour une campagne.
 *
 * @param estimateKg    potentiel du producteur : production attendue en kilos,
 *                      somme des estimations saisies sur ses parcelles
 * @param surfaceHa     superficie totale de ses parcelles retenues
 * @param yieldKgPerHa  rendement à l'hectare, {@code estimateKg / surfaceHa},
 *                      null si la superficie est nulle ou absente
 */
@Schema(description = "Potentiel de production d'un producteur sur une campagne")
public record ProductionPotentialRowDto(
        UUID memberId,
        String memberCode,
        String memberName,
        String sectionName,
        int parcelCount,
        BigDecimal surfaceHa,
        BigDecimal estimateKg,
        BigDecimal yieldKgPerHa
) {}
