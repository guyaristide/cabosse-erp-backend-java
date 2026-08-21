package com.ntech.cabosse.agriculture.harvest.service;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestUpsertDto;
import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
import com.ntech.cabosse.agriculture.harvest.repository.HarvestRepository;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD des récoltes. Snapshots parcelle et membre figés à la création
 * pour rester lisibles si le référentiel évolue.
 */
@ApplicationScoped
public class HarvestService {

    @Inject HarvestRepository harvests;
    @Inject HarvestRefService refService;
    @Inject ParcelRepository parcels;
    @Inject MemberRepository members;
    @Inject CampaignResolver campaignResolver;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public Pagination<HarvestResponseDto> page(UUID parcelId, UUID memberId, UUID campaignId,
                                               String q, PageRequest pr) {
        long total = harvests.countSearch(parcelId, memberId, campaignId, q);
        List<HarvestResponseDto> items =
                harvests.search(parcelId, memberId, campaignId, q, pr.skip(), pr.perPage())
                        .stream()
                        .map(HarvestResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (parcelId != null) filters.put("parcelId", parcelId.toString());
        if (memberId != null) filters.put("memberId", memberId.toString());
        if (campaignId != null) filters.put("campaignId", campaignId.toString());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"harvestDate", "createdAt"}, "desc",
                filters, items);
    }


    /** Toutes les lignes du filtre courant, pour l'export. */
    public List<HarvestResponseDto> listForExport(UUID parcelId, UUID memberId, UUID campaignId, String q) {
        return harvests.search(parcelId, memberId, campaignId, q, 0, Integer.MAX_VALUE)
                .stream().map(HarvestResponseDto::from).toList();
    }

    public HarvestResponseDto getById(UUID id) {
        return HarvestResponseDto.from(loadOrFail(id));
    }

    public HarvestResponseDto create(HarvestUpsertDto payload) {
        ParcelEntity parcel = parcels.findById(payload.parcelId())
                .orElseThrow(() -> new NotFoundException("Parcelle " + payload.parcelId() + " introuvable."));

        HarvestEntity e = new HarvestEntity();
        e.id = idGenerator.newId();
        e.code = refService.next();
        applyPayload(e, payload, parcel);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        harvests.insert(e);
        return HarvestResponseDto.from(e);
    }

    public HarvestResponseDto update(UUID id, HarvestUpsertDto payload) {
        HarvestEntity e = loadOrFail(id);
        ParcelEntity parcel = parcels.findById(payload.parcelId())
                .orElseThrow(() -> new NotFoundException("Parcelle " + payload.parcelId() + " introuvable."));
        applyPayload(e, payload, parcel);
        e.updatedAt = Instant.now();
        harvests.replace(e);
        return HarvestResponseDto.from(e);
    }

    private HarvestEntity loadOrFail(UUID id) {
        return harvests.findById(id)
                .orElseThrow(() -> new NotFoundException("Récolte " + id + " introuvable."));
    }

    private void applyPayload(HarvestEntity e, HarvestUpsertDto p, ParcelEntity parcel) {
        e.parcelId = parcel.id;
        e.parcelCode = parcel.code;
        e.parcelName = parcel.name;
        if (p.memberId() != null) {
            e.memberId = p.memberId();
            e.memberName = members.findById(p.memberId()).map(m -> m.name).orElse(null);
        } else if (parcel.memberId != null) {
            // Fallback : si la parcelle est affectée à un membre, on le prend
            e.memberId = parcel.memberId;
            e.memberName = parcel.memberName;
        } else {
            e.memberId = null;
            e.memberName = null;
        }
        // La campagne fait foi ; l'année n'est qu'une dénormalisation.
        CampaignEntity campaign = campaignResolver.resolve(p.campaignId());
        e.campaignId = campaign.id;
        e.campaignLabel = campaign.label;
        e.campaignYear = campaign.campaignYear;
        e.harvestDate = p.harvestDate();
        e.cabossesKg = p.cabossesKg();
        e.freshBeansKg = p.freshBeansKg();
        e.qualityNotes = blankToNull(p.qualityNotes());
        e.notes = blankToNull(p.notes());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
