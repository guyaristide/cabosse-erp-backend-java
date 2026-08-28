package com.ntech.cabosse.campaign.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
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

    @Inject TenantPreferencesLookup preferences;

    /** Rattachement laissé à la saisie, la campagne courante par défaut. */
    private static final String MANUAL = "MANUAL";

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
            throw new BusinessException(Messages.msg("m.cmp-no-open-campaign"));
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
                    () -> new NotFoundException(Messages.msg("m.cmp-campaign-not-found", campaignId)));
        }
        return repo.findCurrent().orElse(null);
    }

    /**
     * Résolution par la date de l'opération, exigeante.
     *
     * @see #resolveOptionalForDate(LocalDate, UUID)
     */
    public CampaignEntity resolveForDate(LocalDate operationDate, UUID campaignId) {
        CampaignEntity resolved = resolveOptionalForDate(operationDate, campaignId);
        if (resolved == null) {
            throw new BusinessException(Messages.msg("m.cmp-no-open-campaign"));
        }
        return resolved;
    }

    /**
     * Rattache une opération à sa campagne d'après sa propre date.
     *
     * <p>Sans cela, une récolte de novembre saisie en mars entrait dans la
     * campagne de mars : le rattachement suivait le jour de la saisie, pas
     * celui du fait. Les états de campagne s'en trouvaient faussés sans que
     * rien ne le signale.</p>
     *
     * <p>Trois cas, dans cet ordre :</p>
     * <ol>
     *   <li>une campagne explicitement choisie prime toujours, quel que
     *       soit le réglage : c'est une décision humaine ;</li>
     *   <li>en mode {@code DATE} (défaut), la campagne dont la période
     *       couvre la date de l'opération ;</li>
     *   <li>à défaut, et en mode {@code MANUAL}, la campagne courante,
     *       comportement historique.</li>
     * </ol>
     *
     * @param operationDate date métier de l'opération, pas sa date de saisie
     * @param campaignId    campagne explicitement choisie, prioritaire
     */
    public CampaignEntity resolveOptionalForDate(LocalDate operationDate, UUID campaignId) {
        if (campaignId != null) {
            return resolveOptional(campaignId);
        }
        if (MANUAL.equals(preferences.current().campaignAssignmentMode())) {
            return repo.findCurrent().orElse(null);
        }
        return repo.findForDate(operationDate)
                .orElseGet(() -> repo.findCurrent().orElse(null));
    }
}
