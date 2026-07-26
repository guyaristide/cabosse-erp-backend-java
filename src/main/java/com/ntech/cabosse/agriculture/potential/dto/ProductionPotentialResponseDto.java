package com.ntech.cabosse.agriculture.potential.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Potentiel de production de la structure sur une campagne.
 *
 * <p>Le potentiel de la coopérative n'est pas la moyenne des potentiels
 * individuels : c'est la somme des estimations divisée par la somme des
 * superficies. Un producteur de 20 ha pèse vingt fois plus qu'un producteur
 * d'un hectare, et une moyenne simple écraserait cette différence.</p>
 *
 * @param membersWithoutEstimate producteurs actifs sans estimation saisie
 *                               pour la campagne : ils ne sont pas comptés
 *                               dans les totaux et le chiffre doit rester
 *                               visible, sinon la projection paraît complète
 *                               alors qu'elle ne l'est pas
 */
@Schema(description = "Projection du potentiel de production sur une campagne")
public record ProductionPotentialResponseDto(
        UUID campaignId,
        String campaignLabel,
        Integer campaignYear,
        String cropCode,
        int memberCount,
        int parcelCount,
        BigDecimal totalSurfaceHa,
        BigDecimal totalEstimateKg,
        BigDecimal potentialKgPerHa,
        int membersWithoutEstimate,
        List<ProductionPotentialRowDto> rows
) {}
