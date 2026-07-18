package com.ntech.cabosse.members.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cumul des apports de récolte d'un membre par campagne agricole
 * (backlog MEM-04). Le rendement est calculé sur la surface totale des
 * parcelles rattachées au membre ; {@code null} si aucune surface connue.
 */
public record MemberContributionsDto(
        UUID memberId,
        int parcelCount,
        BigDecimal totalSurfaceHa,
        List<CampaignContribution> campaigns
) {
    public record CampaignContribution(
            int campaignYear,
            int harvestCount,
            BigDecimal cabossesKg,
            BigDecimal freshBeansKg,
            /** Fèves fraîches par hectare, sur la surface totale du membre. */
            BigDecimal yieldKgPerHa
    ) {}
}
