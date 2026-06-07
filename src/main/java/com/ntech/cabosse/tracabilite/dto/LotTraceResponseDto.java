package com.ntech.cabosse.tracabilite.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Vue généalogique complète d'un lot — agrégat consommé en un appel par
 * la page traçabilité.
 *
 * @param ref            référence lot ({@code LOT-YYYY-NNNN})
 * @param origin         origine géographique principale ("San Pédro"…)
 * @param origineDesc    description longue de l'origine
 * @param quantity       quantité produite (PF, dans l'unité du PF)
 * @param unit           unité de la quantité
 * @param harvestPeriod  période de récolte / production lisible
 * @param fournisseur    fournisseur principal des matières (le plus fréquent)
 * @param matiere        matière dominante consommée par l'OF
 * @param certifications certifications portées par le lot
 * @param publicUrl      URL publique (à brancher quand le tenant aura un domaine)
 * @param periodLabel    plage temporelle "14–19 mai 2026"
 * @param durationDays   durée de la production en jours
 * @param stages         chaîne des étapes (origine → livraison)
 * @param daughterLots   lots produits à partir de ce lot mère
 */
public record LotTraceResponseDto(
        String ref,
        String origin,
        String origineDesc,
        BigDecimal quantity,
        String unit,
        String harvestPeriod,
        String fournisseur,
        String matiere,
        List<LotCertificationDto> certifications,
        String publicUrl,
        String periodLabel,
        int durationDays,
        List<StageDto> stages,
        List<DaughterLotDto> daughterLots
) {}
