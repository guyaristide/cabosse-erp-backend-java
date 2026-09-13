package com.ntech.cabosse.direction.dto;

import java.time.LocalDate;

/**
 * Vue campagne du tableau de bord Direction (épic CE-196).
 *
 * <p>Agrège les flux estampillés par la campagne : reçus producteurs,
 * ventes négoce, avances aux délégués, trésorerie. Les ventes de produits
 * finis ne portent pas de campagne et n'entrent pas ici : elles restent
 * lues par la vue période.</p>
 */
public record CampaignDashboardDto(
        java.util.UUID campaignId,
        String campaignCode,
        String campaignLabel,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String currency,
        String weightUnit,
        CampaignKpisDto kpis,
        CampaignSynthesisDto synthesis,
        java.util.List<CampaignMonthDto> months,
        java.util.List<CampaignCustomerShareDto> customers,
        /** Ce que chaque label de certification a pesé dans les ventes. */
        java.util.List<CampaignLabelShareDto> labels,
        /**
         * Le barème de collecte du conseil de filière, confronté au
         * réalisé. Absent tant qu'il n'a pas été saisi.
         */
        CampaignScaleDto scale,
        /**
         * Le lecteur voit-il les montants ?
         *
         * <p>Faux, les grandeurs d'argent sont absentes du corps plutôt
         * que vidées : un écran qui affiche des cases vides fait croire à
         * une campagne sans chiffre d'affaires, là où il s'agit d'un
         * droit manquant (13/09/2026).</p>
         */
        boolean financialsVisible,
        java.util.List<CampaignDelegateAdvanceDto> delegates
) {}
