package com.ntech.cabosse.agriculture.parcel.service;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelCampaignYieldDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelUpsertDto;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD parcelles agricoles. Maintient la cohérence bidirectionnelle avec
 * {@link MemberEntity#parcels} : à l'affectation/désaffectation d'un
 * membre, le tableau {@code parcels[]} du membre est mis à jour.
 */
@ApplicationScoped
public class ParcelService {

    @Inject ParcelRepository parcels;
    @Inject ParcelRefService refService;
    @Inject MemberRepository members;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.tenant.capability.TenantCapabilityService capabilities;
    @Inject com.ntech.cabosse.eudr.service.EudrDossierService eudrDossiers;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<ParcelResponseDto> page(String q, ParcelStatus statusFilter, UUID memberId,
                                              PageRequest pr) {
        long total = parcels.countSearch(q, statusFilter, memberId);
        List<ParcelResponseDto> items =
                parcels.search(q, statusFilter, memberId, pr.skip(), pr.perPage()).stream()
                        .map(ParcelResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (statusFilter != null) filters.put("status", statusFilter.name());
        if (memberId != null) filters.put("memberId", memberId.toString());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }


    /** Toutes les lignes du filtre courant, pour l'export. */
    public List<ParcelResponseDto> listForExport(String q, ParcelStatus statusFilter, UUID memberId) {
        return parcels.search(q, statusFilter, memberId, 0, Integer.MAX_VALUE)
                .stream().map(ParcelResponseDto::from).toList();
    }

    public ParcelResponseDto getById(UUID id) {
        return ParcelResponseDto.from(loadOrFail(id));
    }

    // ─── Création ───────────────────────────────────────────────────

    public ParcelResponseDto create(ParcelUpsertDto payload) {
        ParcelEntity e = new ParcelEntity();
        e.id = idGenerator.newId();
        e.code = resolveCode(payload.code());
        applyPayload(e, payload, null);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        parcels.insert(e);
        // Cohérence inverse : si la parcelle est affectée à un membre, l'ajouter à member.parcels.
        if (e.memberId != null) addToMember(e.memberId, e.id);
        // Auto-création du dossier EUDR si la capacité est active. Best-effort :
        // un échec ici ne doit pas faire échouer la création de la parcelle
        // (la régularisation peut se faire à la main plus tard).
        try {
            if (capabilities.has(tenantContext.tenantId(),
                    com.ntech.cabosse.tenant.capability.TenantCapability.HAS_EUDR_COMPLIANCE)) {
                eudrDossiers.bootstrapForParcel(e);
            }
        } catch (RuntimeException ignored) {
            // log silencieux — dossier EUDR créable manuellement plus tard
        }
        return ParcelResponseDto.from(e);
    }

    // ─── Update ─────────────────────────────────────────────────────

    public ParcelResponseDto update(UUID id, ParcelUpsertDto payload) {
        ParcelEntity e = loadOrFail(id);
        UUID previousMember = e.memberId;
        applyPayload(e, payload, previousMember);
        e.updatedAt = Instant.now();
        parcels.replace(e);
        // Cohérence bidirectionnelle : ajustement member.parcels[] si changement.
        if (!java.util.Objects.equals(previousMember, e.memberId)) {
            if (previousMember != null) removeFromMember(previousMember, e.id);
            if (e.memberId != null) addToMember(e.memberId, e.id);
        }
        return ParcelResponseDto.from(e);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private ParcelEntity loadOrFail(UUID id) {
        return parcels.findById(id)
                .orElseThrow(() -> new NotFoundException("Parcelle " + id + " introuvable."));
    }

    private void applyPayload(ParcelEntity e, ParcelUpsertDto p, UUID previousMember) {
        e.name = p.name().trim();
        e.surfaceHa = p.surfaceHa();
        e.gpsPolygon = buildPolygonDocument(p.gpsPolygonCoordinates());
        e.gpsCenter = p.gpsCenter() != null ? new ArrayList<>(p.gpsCenter()) : null;
        e.variety = blankToNull(p.variety());
        e.cropCode = blankToNull(p.cropCode());
        e.mainCrop = Boolean.TRUE.equals(p.mainCrop());
        e.plantingDate = p.plantingDate();
        // L'année reste la donnée de référence des lecteurs existants
        // (registre, âge de la plantation) : dérivée dès qu'une date complète
        // est saisie, sinon conservée telle quelle. Integer.valueOf() est
        // indispensable : sans lui, le ternaire s'aligne sur le int de
        // getYear() et déréférence un plantingYear null.
        e.plantingYear = p.plantingDate() != null
                ? Integer.valueOf(p.plantingDate().getYear())
                : p.plantingYear();
        e.regionCode = blankToNull(p.regionCode());
        e.departmentCode = blankToNull(p.departmentCode());
        e.certifications = p.certifications() != null
                ? new ArrayList<>(p.certifications())
                : new ArrayList<>();
        e.campaignYields = p.campaignYields() == null ? new ArrayList<>()
                : p.campaignYields().stream()
                        .filter(y -> y != null && y.campaignId() != null)
                        .map(ParcelCampaignYieldDto::toEntity)
                        .collect(java.util.stream.Collectors.toList());
        e.memberId = p.memberId();
        // Snapshot du nom membre pour les listes
        if (e.memberId != null) {
            e.memberName = members.findById(e.memberId).map(m -> m.name).orElse(null);
        } else {
            e.memberName = null;
        }
        e.status = p.status();
        e.notes = blankToNull(p.notes());
    }

    private Document buildPolygonDocument(List<List<List<Double>>> coords) {
        if (coords == null || coords.isEmpty()) return null;
        return new Document("type", "Polygon").append("coordinates", coords);
    }

    private void addToMember(UUID memberId, UUID parcelId) {
        members.findById(memberId).ifPresent(m -> {
            if (m.parcels == null) m.parcels = new ArrayList<>();
            if (!m.parcels.contains(parcelId)) {
                m.parcels.add(parcelId);
                m.updatedAt = Instant.now();
                members.replace(m);
            }
        });
    }

    private void removeFromMember(UUID memberId, UUID parcelId) {
        members.findById(memberId).ifPresent(m -> {
            if (m.parcels != null && m.parcels.remove(parcelId)) {
                m.updatedAt = Instant.now();
                members.replace(m);
            }
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Résout le code d'une parcelle à la création : code saisi (unique) sinon
     * séquence {@code PR-YYYY-NNNN} (backlog PARC-01).
     */
    private String resolveCode(String provided) {
        if (provided == null || provided.isBlank()) {
            return refService.next();
        }
        String code = provided.trim();
        if (parcels.codeExists(code)) {
            throw new BusinessException("Une parcelle avec le code « " + code + " » existe déjà.");
        }
        return code;
    }
}
