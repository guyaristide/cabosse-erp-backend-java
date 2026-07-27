package com.ntech.cabosse.agriculture.potential.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Potentiel de production de la structure sur une campagne.
 *
 * <p>Deux grandeurs distinctes, souvent confondues. Le <strong>potentiel de
 * la structure</strong> ({@code totalEstimateKg}) est la production attendue
 * en kilos : la somme des estimations. Le <strong>rendement à l'hectare</strong>
 * ({@code yieldKgPerHa}) rapporte cette production à la surface : somme des
 * estimations divisée par somme des superficies, jamais la moyenne des
 * rendements individuels, qui ferait peser un hectare autant que vingt.</p>
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
        BigDecimal yieldKgPerHa,
        int membersWithoutEstimate,
        List<ProductionPotentialRowDto> rows
) {}
