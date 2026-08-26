package com.ntech.cabosse.agriculture.potential.service;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelCampaignYield;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.agriculture.potential.dto.ProductionPotentialResponseDto;
import com.ntech.cabosse.agriculture.potential.dto.ProductionPotentialRowDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projection du potentiel de production d'une campagne, à partir des
 * estimations déjà saisies sur les parcelles (backlog PARC-01).
 *
 * <p>C'est ainsi que la coopérative construit sa prévision : elle part du
 * potentiel de chaque producteur, pas d'un objectif global. Le potentiel
 * est une production attendue en kilos ; le rendement à l'hectare la
 * rapporte à la surface. Celui de la structure vaut somme des estimations
 * divisée par somme des superficies : moyenner les rendements individuels
 * donnerait un chiffre faux, en pondérant pareil un hectare et vingt.</p>
 *
 * <p>Aucune donnée nouvelle n'est demandée : la projection se lit dans ce
 * qui est déjà renseigné sur le producteur et ses parcelles.</p>
 */
@ApplicationScoped
public class ProductionPotentialService {

    /** Décimales du rendement kg/ha. Deux suffisent à comparer des producteurs. */
    private static final int RATIO_SCALE = 2;

    @Inject MemberRepository members;
    @Inject ParcelRepository parcels;
    @Inject SectionRepository sections;
    @Inject CampaignService campaigns;

    /**
     * @param campaignId campagne visée ; campagne courante si null
     * @param cropCode   restreint aux parcelles d'une culture ; toutes si null
     */
    public ProductionPotentialResponseDto compute(UUID campaignId, String cropCode) {
        CampaignEntity campaign = campaignId != null ? campaigns.get(campaignId) : campaigns.current();
        if (campaign == null) {
            throw new BusinessException(Messages.msg("m.agr-no-open-campaign-potential"));
        }

        Map<UUID, String> sectionNames = new HashMap<>();
        sections.listAll().forEach(s -> sectionNames.put(s.id, s.name));

        Map<UUID, List<ParcelEntity>> parcelsByMember = new HashMap<>();
        for (ParcelEntity p : parcels.listAll()) {
            if (p.memberId == null) continue;
            if (p.status == ParcelStatus.ABANDONED) continue;
            if (cropCode != null && !cropCode.isBlank() && !cropCode.equals(p.cropCode)) continue;
            parcelsByMember.computeIfAbsent(p.memberId, k -> new ArrayList<>()).add(p);
        }

        List<ProductionPotentialRowDto> rows = new ArrayList<>();
        BigDecimal totalSurface = BigDecimal.ZERO;
        BigDecimal totalEstimate = BigDecimal.ZERO;
        int totalParcels = 0;
        int withoutEstimate = 0;

        for (MemberEntity m : members.listAll()) {
            if (m.status != MemberStatus.ACTIVE) continue;
            List<ParcelEntity> memberParcels = parcelsByMember.getOrDefault(m.id, List.of());
            if (memberParcels.isEmpty()) continue;

            BigDecimal surface = BigDecimal.ZERO;
            BigDecimal estimate = BigDecimal.ZERO;
            boolean hasEstimate = false;

            for (ParcelEntity p : memberParcels) {
                if (p.surfaceHa != null) surface = surface.add(p.surfaceHa);
                BigDecimal parcelEstimate = estimateFor(p, campaign);
                if (parcelEstimate != null) {
                    estimate = estimate.add(parcelEstimate);
                    hasEstimate = true;
                }
            }

            if (!hasEstimate) {
                // Producteur sans estimation : hors totaux, mais compté à part
                // pour que la couverture de la projection reste lisible.
                withoutEstimate++;
                continue;
            }

            rows.add(new ProductionPotentialRowDto(
                    m.id, m.code, m.name,
                    m.sectionId != null ? sectionNames.get(m.sectionId) : null,
                    memberParcels.size(),
                    surface, estimate,
                    ratio(estimate, surface)));

            totalSurface = totalSurface.add(surface);
            totalEstimate = totalEstimate.add(estimate);
            totalParcels += memberParcels.size();
        }

        rows.sort(Comparator.comparing(ProductionPotentialRowDto::estimateKg,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return new ProductionPotentialResponseDto(
                campaign.id, campaign.label, campaign.campaignYear,
                cropCode != null && !cropCode.isBlank() ? cropCode : null,
                rows.size(), totalParcels,
                totalSurface, totalEstimate,
                ratio(totalEstimate, totalSurface),
                withoutEstimate,
                List.copyOf(rows));
    }

    private static BigDecimal estimateFor(ParcelEntity p, CampaignEntity campaign) {
        if (p.campaignYields == null) return null;
        for (ParcelCampaignYield y : p.campaignYields) {
            if (y != null && campaign.id.equals(y.campaignId) && y.estimateKg != null) {
                return y.estimateKg;
            }
        }
        return null;
    }

    /** Null plutôt que zéro quand la superficie manque : un ratio sans base n'existe pas. */
    private static BigDecimal ratio(BigDecimal estimate, BigDecimal surface) {
        if (estimate == null || surface == null || surface.signum() <= 0) return null;
        return estimate.divide(surface, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
