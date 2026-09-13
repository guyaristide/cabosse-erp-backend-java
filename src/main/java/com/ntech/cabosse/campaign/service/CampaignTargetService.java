package com.ntech.cabosse.campaign.service;

import com.ntech.cabosse.campaign.dto.CampaignTargetDto;
import com.ntech.cabosse.campaign.dto.CampaignTargetUpsertDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignTargetEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.campaign.repository.CampaignTargetRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les objectifs mensuels d'une campagne.
 *
 * <p>Demandés par la coopérative le 13/09/2026 : ses tableaux de bord
 * confrontent chaque mois le réalisé à un objectif et affichent l'écart.
 * Le réalisé se calcule déjà de bout en bout ; l'objectif est une
 * décision, il ne se déduit de rien et doit se saisir.</p>
 *
 * <p>Un mois sans objectif n'est pas un objectif à zéro. L'écart ne se
 * calcule alors pas, plutôt que d'annoncer un retard que personne n'a
 * décidé.</p>
 */
@ApplicationScoped
public class CampaignTargetService {

    @Inject CampaignTargetRepository repo;
    @Inject CampaignRepository campaigns;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    public List<CampaignTargetDto> list(UUID campaignId) {
        loadOrFail(campaignId);
        return repo.listByCampaign(campaignId).stream().map(CampaignTargetDto::from).toList();
    }

    /** Les objectifs indexés par mois, pour le tableau de bord. */
    public Map<String, CampaignTargetEntity> byMonth(UUID campaignId) {
        Map<String, CampaignTargetEntity> out = new HashMap<>();
        for (CampaignTargetEntity e : repo.listByCampaign(campaignId)) {
            out.put(e.month, e);
        }
        return out;
    }

    /**
     * Pose ou corrige l'objectif d'un mois.
     *
     * <p>Les deux montants absents effacent l'objectif plutôt que de
     * l'enregistrer à vide : sans cela, un mois vidé garderait une cible
     * fantôme contre laquelle l'écart continuerait de se calculer.</p>
     */
    public CampaignTargetDto upsert(UUID campaignId, CampaignTargetUpsertDto payload) {
        CampaignEntity campaign = loadOrFail(campaignId);
        ensureWithinCampaign(campaign, payload.month());

        if (payload.collectionTargetKg() == null && payload.saleTargetKg() == null) {
            repo.delete(campaignId, payload.month());
            return new CampaignTargetDto(payload.month(), null, null, Instant.now(), actor());
        }

        // L'identifiant d'un document ne se remplace pas : on reprend
        // celui qui existe, sinon Mongo refuse le remplacement.
        CampaignTargetEntity e = repo.find(campaignId, payload.month())
                .orElseGet(CampaignTargetEntity::new);
        if (e.id == null) e.id = idGenerator.newId();
        e.campaignId = campaignId;
        e.month = payload.month();
        e.collectionTargetKg = payload.collectionTargetKg();
        e.saleTargetKg = payload.saleTargetKg();
        e.updatedAt = Instant.now();
        e.updatedByEmail = actor();
        repo.upsert(e);
        return CampaignTargetDto.from(e);
    }

    /**
     * Un objectif posé hors de la campagne ne serait confronté à rien :
     * le tableau de bord ne parcourt que les mois de la campagne, et la
     * cible resterait invisible sans que personne comprenne pourquoi.
     */
    private void ensureWithinCampaign(CampaignEntity campaign, String month) {
        YearMonth target = YearMonth.parse(month);
        if (campaign.startDate != null && target.isBefore(YearMonth.from(campaign.startDate))) {
            throw new BusinessException(Messages.msg("m.cmp-target-out-of-range", month));
        }
        if (campaign.endDate != null && target.isAfter(YearMonth.from(campaign.endDate))) {
            throw new BusinessException(Messages.msg("m.cmp-target-out-of-range", month));
        }
    }

    private CampaignEntity loadOrFail(UUID campaignId) {
        return campaigns.findById(campaignId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cmp-not-found")));
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** Zéro n'est pas l'absence : seul un objectif saisi donne un écart. */
    public static BigDecimal gap(BigDecimal actual, BigDecimal target) {
        if (target == null) return null;
        return (actual == null ? BigDecimal.ZERO : actual).subtract(target);
    }
}
