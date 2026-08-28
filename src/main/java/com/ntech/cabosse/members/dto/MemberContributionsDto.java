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
            UUID campaignId,
            String campaignLabel,
            int harvestCount,
            BigDecimal cabossesKg,
            BigDecimal freshBeansKg,
            /**
             * Production réelle : le poids effectivement livré et payé,
             * somme des reçus d'achat de la campagne.
             *
             * <p>C'est la seule quantité que la coopérative pèse
             * réellement. Les cabosses et les fèves fraîches ne sont
             * mesurées que par les structures qui fermentent elles-mêmes ;
             * ailleurs, le producteur sèche sa récolte et c'est la fève
             * sèche qui arrive à la bascule.</p>
             */
            BigDecimal deliveredKg,
            /** Fèves fraîches par hectare, sur la surface totale du membre. */
            BigDecimal yieldKgPerHa
    ) {}
}
