package com.ntech.cabosse.supplier.service;

import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.dto.CollectorMarginsDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contrôle une liste de taux négociés campagne par campagne.
 *
 * <p>La rémunération d'un délégué se posait depuis la fiche du
 * producteur, et de là seulement. Un délégué qui n'est pas sociétaire n'a
 * pas de fiche producteur : il ne pouvait recevoir aucun taux négocié, et
 * le calcul retombait en silence sur le taux commun (relevé le
 * 28/09/2026). Le même contrôle sert maintenant aux deux portes, pour
 * qu'elles ne divergent pas.</p>
 */
@ApplicationScoped
public class CampaignMarginValidator {

    @Inject CampaignRepository campaigns;

    /**
     * Rend les taux prêts à être posés, ou refuse.
     *
     * <p>Deux taux pour une même campagne rendraient le résultat
     * dépendant de l'ordre de la liste, et une campagne inconnue
     * laisserait un taux que rien n'appliquerait jamais.</p>
     */
    public List<SupplierEntity.CampaignMargin> validated(CollectorMarginsDto payload) {
        List<SupplierEntity.CampaignMargin> margins = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (var entry : payload == null || payload.margins() == null
                ? List.<CollectorMarginsDto.Entry>of() : payload.margins()) {
            if (!seen.add(entry.campaignId())) {
                throw new BusinessException(
                        Messages.msg("m.mem-margin-duplicate-campaign", entry.campaignId()));
            }
            campaigns.findById(entry.campaignId()).orElseThrow(() -> new NotFoundException(
                    Messages.msg("m.cmp-campaign-not-found", entry.campaignId())));
            margins.add(new SupplierEntity.CampaignMargin(entry.campaignId(), entry.rate()));
        }
        return margins;
    }
}
