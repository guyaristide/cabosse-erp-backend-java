package com.ntech.cabosse.members.register;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelCampaignYield;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.department.repository.DepartmentRepository;
import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.region.repository.RegionRepository;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Construit le registre producteurs (backlog REG-01) pour une campagne :
 * une ligne par parcelle (le producteur se répète), valeurs résolues et
 * dénormalisées, au format du fichier modèle.
 */
@ApplicationScoped
public class MemberRegisterService {

    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject MemberRepository members;
    @Inject ParcelRepository parcels;
    @Inject SectionRepository sections;
    @Inject RegionRepository regions;
    @Inject DepartmentRepository departments;
    @Inject CampaignService campaigns;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    /** Registre prêt à exporter (titre + colonnes du modèle + lignes). */
    public ExportDataset<RegisterRow> dataset(UUID campaignId) {
        MemberRegister reg = build(campaignId);
        return new ExportDataset<>(reg.title(), RegisterExportColumns.all(), reg.rows());
    }

    public MemberRegister build(UUID campaignId) {
        CampaignEntity campaign = campaignId != null ? campaigns.get(campaignId) : campaigns.current();
        Integer campaignYear = campaign != null ? campaign.campaignYear : null;
        String title = "Registre producteurs — "
                + (campaign != null ? campaign.label + " (" + campaign.campaignYear + ")" : "toutes campagnes");

        String coopName = coopName();
        Map<UUID, String> sectionNames = sections.listAll().stream()
                .collect(Collectors.toMap(s -> s.id, s -> s.name, (a, b) -> a));
        Map<String, String> regionNames = regions.listAll().stream()
                .collect(Collectors.toMap(r -> r.code, r -> r.name, (a, b) -> a));
        Map<String, String> deptNames = departments.listAll().stream()
                .collect(Collectors.toMap(d -> d.code, d -> d.name, (a, b) -> a));

        List<MemberEntity> allMembers = members.listAll();
        Map<UUID, MemberEntity> membersById = allMembers.stream()
                .collect(Collectors.toMap(m -> m.id, Function.identity(), (a, b) -> a));
        Map<UUID, List<ParcelEntity>> parcelsByMember = parcels.listAll().stream()
                .filter(p -> p.memberId != null)
                .filter(p -> p.status != com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus.ABANDONED)
                .collect(Collectors.groupingBy(p -> p.memberId));

        List<RegisterRow> rows = new ArrayList<>();
        int no = 1;
        for (MemberEntity m : allMembers) {
            if (m.status != MemberStatus.ACTIVE) continue;
            List<ParcelEntity> memberParcels = parcelsByMember.getOrDefault(m.id, List.of());
            if (memberParcels.isEmpty()) {
                rows.add(row(no++, coopName, m, null, campaign, campaignYear,
                        sectionNames, regionNames, deptNames, membersById));
            } else {
                for (ParcelEntity p : memberParcels) {
                    rows.add(row(no++, coopName, m, p, campaign, campaignYear,
                            sectionNames, regionNames, deptNames, membersById));
                }
            }
        }
        return new MemberRegister(title, rows);
    }

    private RegisterRow row(int no, String coopName, MemberEntity m, ParcelEntity p,
                            CampaignEntity campaign, Integer campaignYear,
                            Map<UUID, String> sectionNames, Map<String, String> regionNames,
                            Map<String, String> deptNames, Map<UUID, MemberEntity> membersById) {

        MemberEntity agent = m.followUpAgentMemberId != null
                ? membersById.get(m.followUpAgentMemberId) : null;

        ParcelCampaignYield yield = null;
        if (p != null && p.campaignYields != null && campaign != null) {
            yield = p.campaignYields.stream()
                    .filter(y -> campaign.id.equals(y.campaignId))
                    .findFirst().orElse(null);
        }

        return new RegisterRow(
                no,
                coopName,
                m.code,
                externalCodes(m),
                m.name,
                birth(m),
                m.idDocType,
                m.idDocNumber,
                sexe(m),
                m.sectionId != null ? sectionNames.get(m.sectionId) : null,
                m.village,
                p != null ? p.code : null,
                p != null ? p.plantingYear : null,
                age(campaignYear, p),
                p != null ? p.surfaceHa : null,
                yield != null ? yield.yieldPerHa : null,
                yield != null ? yield.estimateKg : null,
                latitude(p),
                longitude(p),
                p != null && p.departmentCode != null ? deptNames.get(p.departmentCode) : null,
                p != null && p.regionCode != null ? regionNames.get(p.regionCode) : null,
                agent != null ? agent.name : null,
                agent != null ? agent.phone : null
        );
    }

    private String coopName() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        return t != null ? t.name : null;
    }

    private static String externalCodes(MemberEntity m) {
        if (m.externalProducerCodes == null || m.externalProducerCodes.isEmpty()) return null;
        String joined = m.externalProducerCodes.stream()
                .filter(c -> c.number != null && !c.number.isBlank())
                .map(c -> c.number.trim())
                .collect(Collectors.joining(" / "));
        return joined.isBlank() ? null : joined;
    }

    private static String birth(MemberEntity m) {
        if (m.birthDate != null) return m.birthDate.format(DATE_FR);
        if (m.birthYear != null) return String.valueOf(m.birthYear);
        return null;
    }

    /**
     * Sexe du producteur. Lit le champ dédié (MEM-07) et retombe sur le
     * champ legacy pour les fiches non encore rouvertes après la migration.
     */
    private static String sexe(MemberEntity m) {
        if (m.gender == MemberGender.MALE) return "M";
        if (m.gender == MemberGender.FEMALE) return "F";
        if (m.civilStatus == MemberCivilStatus.MALE) return "M";
        if (m.civilStatus == MemberCivilStatus.FEMALE) return "F";
        return null;
    }

    private static Integer age(Integer campaignYear, ParcelEntity p) {
        if (campaignYear == null || p == null || p.plantingYear == null) return null;
        int age = campaignYear - p.plantingYear;
        return age >= 0 ? age : null;
    }

    private static Double latitude(ParcelEntity p) {
        return p != null && p.gpsCenter != null && p.gpsCenter.size() >= 2 ? p.gpsCenter.get(1) : null;
    }

    private static Double longitude(ParcelEntity p) {
        return p != null && p.gpsCenter != null && p.gpsCenter.size() >= 2 ? p.gpsCenter.get(0) : null;
    }
}
