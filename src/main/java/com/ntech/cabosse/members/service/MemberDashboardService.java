package com.ntech.cabosse.members.service;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.agriculture.potential.service.ProductionPotentialService;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.members.dto.MemberDashboardDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.List;
import java.util.UUID;

/**
 * Le portrait du sociétariat.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Aucun chiffre n'est
 * nouveau : ils vivent tous dans les fiches producteurs et les parcelles,
 * mais il fallait les compter à la main pour répondre à « combien de
 * femmes », « quel âge ont nos plantations », « combien d'hectares ».</p>
 *
 * <p>Deux partis pris de lecture. Les producteurs <strong>radiés</strong>
 * ne comptent pas : le portrait décrit qui est là, pas qui est passé. Et
 * une moyenne annonce toujours sur combien de fiches elle est établie :
 * un âge moyen calculé sur un tiers du registre ne vaut pas un âge moyen
 * complet, et taire l'écart ferait prendre l'un pour l'autre.</p>
 */
@ApplicationScoped
public class MemberDashboardService {

    @Inject MemberRepository members;
    @Inject ParcelRepository parcels;
    @Inject SectionRepository sections;
    @Inject ProductionPotentialService potential;

    public MemberDashboardDto compute(UUID campaignId, String cropCode) {
        List<MemberEntity> all = members.listAll().stream()
                .filter(m -> m.status != MemberStatus.RETIRED)
                .toList();

        int men = 0;
        int women = 0;
        int unknown = 0;
        long ageSum = 0;
        int withAge = 0;
        int thisYear = Year.now().getValue();
        for (MemberEntity m : all) {
            if (m.gender == MemberGender.MALE) men++;
            else if (m.gender == MemberGender.FEMALE) women++;
            else unknown++;

            Integer birthYear = birthYearOf(m);
            if (birthYear != null && birthYear > 1900 && birthYear <= thisYear) {
                ageSum += thisYear - birthYear;
                withAge++;
            }
        }

        // Le pourcentage de femmes se rapporte aux fiches qui portent un
        // genre : compter les inconnues au dénominateur ferait baisser le
        // taux à chaque fiche mal remplie, ce qui se lirait comme un recul
        // du sociétariat féminin.
        int gendered = men + women;
        BigDecimal womenShare = gendered == 0 ? null
                : BigDecimal.valueOf(women).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(gendered), 1, RoundingMode.HALF_UP);

        // Une parcelle abandonnée ne fait plus partie du patrimoine :
        // la compter gonflerait la surface d'hectares qui ne produisent
        // plus. La jachère et la replantation restent, elles reviendront.
        List<ParcelEntity> activeParcels = parcels.listAll().stream()
                .filter(p -> p.status != ParcelStatus.ABANDONED)
                .toList();

        BigDecimal surface = BigDecimal.ZERO;
        long plantationAgeSum = 0;
        int withPlantingYear = 0;
        Integer youngest = null;
        Integer oldest = null;
        for (ParcelEntity p : activeParcels) {
            if (p.surfaceHa != null) surface = surface.add(p.surfaceHa);
            Integer planted = p.plantingYear;
            if (planted == null || planted <= 1900 || planted > thisYear) continue;
            int age = thisYear - planted;
            plantationAgeSum += age;
            withPlantingYear++;
            if (youngest == null || age < youngest) youngest = age;
            if (oldest == null || age > oldest) oldest = age;
        }

        BigDecimal potentialKg = null;
        BigDecimal yield = null;
        if (campaignId != null) {
            var projection = potential.compute(campaignId, cropCode);
            potentialKg = projection.totalEstimateKg();
            yield = projection.yieldKgPerHa();
        }

        return new MemberDashboardDto(
                all.size(), men, women, unknown, womenShare,
                average(ageSum, withAge), withAge,
                average(plantationAgeSum, withPlantingYear), youngest, oldest, withPlantingYear,
                surface, perMember(surface, all.size()), activeParcels.size(),
                potentialKg, yield,
                sections.listAll().size());
    }

    /** L'année de naissance, qu'elle vienne d'une date complète ou d'elle seule. */
    private static Integer birthYearOf(MemberEntity m) {
        if (m.birthDate != null) return m.birthDate.getYear();
        return m.birthYear;
    }

    private static BigDecimal average(long sum, int count) {
        if (count == 0) return null;
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    /**
     * La surface par membre rapporte les hectares à <em>tous</em> les
     * producteurs, y compris ceux qui n'ont pas encore de parcelle
     * enregistrée : c'est la question posée, et ne diviser que par les
     * propriétaires connus gonflerait la moyenne à mesure que le registre
     * se remplit.
     */
    private static BigDecimal perMember(BigDecimal surface, int memberCount) {
        if (memberCount == 0) return null;
        return surface.divide(BigDecimal.valueOf(memberCount), 2, RoundingMode.HALF_UP);
    }
}
