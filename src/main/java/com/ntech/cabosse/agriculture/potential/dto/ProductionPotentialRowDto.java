package com.ntech.cabosse.agriculture.potential.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Potentiel de production d'un producteur pour une campagne.
 *
 * @param estimateKg        estimation de production saisie sur ses parcelles
 * @param surfaceHa         superficie totale de ses parcelles retenues
 * @param potentialKgPerHa  {@code estimateKg / surfaceHa}, null si la
 *                          superficie est nulle ou absente
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
        BigDecimal potentialKgPerHa
) {}
