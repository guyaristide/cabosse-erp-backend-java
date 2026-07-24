package com.ntech.cabosse.search.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Résultat unitaire de la recherche globale. Le {@code type} identifie
 * l'entité ({@code customer}, {@code supplier}, {@code article},
 * {@code member}, {@code purchaseOrder}, {@code sale}, {@code lot}) ; le
 * frontend en dérive la route de navigation. {@code id} est l'identifiant
 * (UUID, ou {@code lotRef} pour un lot).
 */
@Schema(description = "Résultat de recherche globale")
public record SearchHitDto(
        String type,
        String id,
        String label,
        String sublabel) {}
