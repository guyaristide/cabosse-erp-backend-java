package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Le portrait du sociétariat, en une lecture.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Les chiffres existaient
 * tous dans les fiches, mais il fallait les compter à la main pour
 * répondre à « combien de femmes », « quel âge ont nos plantations » ou
 * « combien d'hectares avons-nous ».</p>
 *
 * <p>Les âges se calculent sur les fiches qui portent l'information, et
 * le nombre de fiches retenues est annoncé : une moyenne d'âge établie
 * sur un tiers du registre n'a pas le même sens qu'une moyenne complète,
 * et masquer l'écart ferait prendre l'une pour l'autre.</p>
 */
@Schema(description = "Tableau de bord du sociétariat")
public record MemberDashboardDto(
        int totalMembers,
        int menCount,
        int womenCount,
        /** Fiches sans genre renseigné : elles ne pèsent sur aucun ratio. */
        int unknownGenderCount,
        BigDecimal womenSharePct,

        BigDecimal averageAgeYears,
        /** Fiches portant une date ou une année de naissance. */
        int membersWithAge,

        BigDecimal averagePlantationAgeYears,
        Integer youngestPlantationYears,
        Integer oldestPlantationYears,
        int parcelsWithPlantingYear,

        BigDecimal totalSurfaceHa,
        BigDecimal averageSurfaceHaPerMember,
        int parcelCount,

        /** Projection de campagne, absente si aucune campagne n'est demandée. */
        BigDecimal totalPotentialKg,
        BigDecimal yieldKgPerHa,

        int sectionCount
) {}
