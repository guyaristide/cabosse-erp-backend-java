package com.ntech.cabosse.analytics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Une ligne de l'état analytique par centre de coût (backlog CPT-09/CPT-17) :
 * total des charges (classe 6, débit − crédit) imputées au centre sur la
 * période, et — quand le centre porte un volume d'activité homogène — le
 * coût unitaire (charges / volume). Le code {@code null} regroupe les
 * charges non affectées.
 *
 * <p>{@code volumeQuantity}/{@code unit}/{@code unitCost} sont
 * {@code null} si le centre n'a pas de volume sur la base choisie, ou si
 * ses articles mélangent des unités ({@code mixedUnits = true}) — auquel
 * cas un coût unitaire agrégé n'aurait pas de sens.</p>
 */
@Schema(description = "Charges et coût unitaire d'un centre de coût sur une période")
public record CostCenterReportRowDto(
        String code,
        String name,
        BigDecimal charges,
        BigDecimal volumeQuantity,
        String unit,
        BigDecimal unitCost,
        boolean mixedUnits
) {
    /** Ligne sans volume d'activité (compat CPT-09). */
    public static CostCenterReportRowDto charges(String code, String name, BigDecimal charges) {
        return new CostCenterReportRowDto(code, name, charges, null, null, null, false);
    }
}
