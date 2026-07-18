package com.ntech.cabosse.members.service;

import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
import com.ntech.cabosse.agriculture.harvest.repository.HarvestRepository;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.members.dto.MemberContributionsDto;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Agrégat des apports de récolte d'un membre (backlog MEM-04) : cumul
 * par campagne agricole + rendement rapporté à la surface des parcelles
 * rattachées. Socle du calcul de rémunération campagne (Phase 5 du plan
 * CDC v3).
 */
@ApplicationScoped
public class MemberContributionsService {

    @Inject MemberRepository members;
    @Inject HarvestRepository harvests;
    @Inject ParcelRepository parcels;

    public MemberContributionsDto contributions(UUID memberId) {
        members.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Membre " + memberId + " introuvable."));

        List<ParcelEntity> memberParcels = parcels.search(null, null, memberId, 0, 1000);
        BigDecimal totalSurface = memberParcels.stream()
                .map(p -> p.surfaceHa != null ? p.surfaceHa : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Integer, List<HarvestEntity>> byCampaign = new TreeMap<>(Comparator.reverseOrder());
        for (HarvestEntity h : harvests.listByMember(memberId)) {
            byCampaign.computeIfAbsent(h.campaignYear, k -> new ArrayList<>()).add(h);
        }

        List<MemberContributionsDto.CampaignContribution> campaigns = new ArrayList<>();
        for (Map.Entry<Integer, List<HarvestEntity>> en : byCampaign.entrySet()) {
            BigDecimal cabosses = BigDecimal.ZERO;
            BigDecimal freshBeans = BigDecimal.ZERO;
            for (HarvestEntity h : en.getValue()) {
                if (h.cabossesKg != null) cabosses = cabosses.add(h.cabossesKg);
                if (h.freshBeansKg != null) freshBeans = freshBeans.add(h.freshBeansKg);
            }
            BigDecimal yield = totalSurface.signum() > 0
                    ? freshBeans.divide(totalSurface, 1, RoundingMode.HALF_UP)
                    : null;
            campaigns.add(new MemberContributionsDto.CampaignContribution(
                    en.getKey(), en.getValue().size(), cabosses, freshBeans, yield));
        }

        return new MemberContributionsDto(memberId, memberParcels.size(), totalSurface, campaigns);
    }
}
