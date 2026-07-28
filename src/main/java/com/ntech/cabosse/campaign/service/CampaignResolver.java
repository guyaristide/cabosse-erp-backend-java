package com.ntech.cabosse.campaign.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Résout la campagne à rattacher à une opération.
 *
 * <p>Tous les flux datés par une campagne (récoltes, avances aux délégués,
 * achats aux producteurs, ventes) passent par ici : la règle de choix doit
 * être la même partout, sinon deux écrans rattachent différemment la même
 * opération.</p>
 *
 * <p>Ordre de préférence : la campagne demandée explicitement, sinon la
 * campagne ouverte. Aucun repli par année : une année civile peut porter
 * plusieurs campagnes, elle ne permet donc pas de trancher.</p>
 */
@ApplicationScoped
public class CampaignResolver {

    // Lecture directe du référentiel : passer par CampaignService
    // rejouerait son contrôle de capacité, et une avance légitime se
    // verrait refusée à cause d'un module tiers.
    @Inject CampaignRepository repo;

    /**
     * Résolution exigeante, pour les opérations qui n'ont pas de sens hors
     * campagne (une récolte, par exemple).
     *
     * @param campaignId campagne explicitement choisie, prioritaire
     * @return la campagne retenue, jamais null
     * @throws BusinessException si rien ne permet de trancher
     */
    public CampaignEntity resolve(UUID campaignId) {
        CampaignEntity resolved = resolveOptional(campaignId);
        if (resolved == null) {
            throw new BusinessException(
                    "Aucune campagne ouverte : créez une campagne avant d'enregistrer cette opération.");
        }
        return resolved;
    }

    /**
     * Même règle, mais tolérante : renvoie null quand rien ne permet de
     * trancher. Pour les opérations où la campagne est une information de
     * rattachement, pas une condition d'existence — une avance consentie
     * hors campagne reste une avance.
     */
    public CampaignEntity resolveOptional(UUID campaignId) {
        if (campaignId != null) {
            return repo.findById(campaignId).orElseThrow(
                    () -> new NotFoundException("Campagne " + campaignId + " introuvable"));
        }
        return repo.findCurrent().orElse(null);
    }
}
