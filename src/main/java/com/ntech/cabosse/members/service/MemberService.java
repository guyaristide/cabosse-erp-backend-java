package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD des membres-producteurs. Auto-crée un {@link SupplierEntity}
 * miroir à la création pour que les flux d'achats (BC, RD) puissent
 * référencer un fournisseur sans modification — cf. NEIBA-TECH-2026-003 §2.
 *
 * <p>Toute mutation de membre est garde-fou : le service présuppose que
 * la capacité {@code HAS_MEMBERS} est active pour le tenant courant.
 * La vérification est faite au niveau du {@code MemberResource}
 * (RolesAllowed + filtre capability).</p>
 */
@ApplicationScoped
public class MemberService {

    @Inject MemberRepository members;
    @Inject MemberRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<MemberResponseDto> page(String q,
                                              com.ntech.cabosse.members.entity.MemberStatus statusFilter,
                                              PageRequest pr) {
        long total = members.countSearch(q, statusFilter);
        List<MemberResponseDto> items = members.search(q, statusFilter, pr.skip(), pr.perPage())
                .stream()
                .map(MemberResponseDto::from)
                .toList();
        Map<String, String> filters = new HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (statusFilter != null) filters.put("status", statusFilter.name());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public MemberResponseDto getById(UUID id) {
        return MemberResponseDto.from(loadOrFail(id));
    }

    // ─── Création ───────────────────────────────────────────────────

    public MemberResponseDto create(MemberUpsertDto payload) {
        MemberEntity e = new MemberEntity();
        e.id = idGenerator.newId();
        e.code = refService.next();
        applyPayload(e, payload);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // Auto-création du SupplierEntity miroir.
        SupplierEntity supplier = createMirrorSupplier(e);
        e.supplierId = supplier.id;

        members.insert(e);
        return MemberResponseDto.from(e);
    }

    // ─── Update ─────────────────────────────────────────────────────

    public MemberResponseDto update(UUID id, MemberUpsertDto payload) {
        MemberEntity e = loadOrFail(id);
        applyPayload(e, payload);
        e.updatedAt = Instant.now();
        members.replace(e);

        // Synchroniser le supplier miroir (nom + contact).
        if (e.supplierId != null) {
            suppliers.findById(e.supplierId).ifPresent(s -> {
                s.name = e.name;
                s.phone = e.phone;
                s.email = e.email;
                s.cityName = e.village;
                s.updatedAt = Instant.now();
                suppliers.replace(s);
            });
        }
        return MemberResponseDto.from(e);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private MemberEntity loadOrFail(UUID id) {
        return members.findById(id)
                .orElseThrow(() -> new NotFoundException("Membre " + id + " introuvable."));
    }

    private void applyPayload(MemberEntity e, MemberUpsertDto p) {
        e.name = p.name().trim();
        e.civilStatus = p.civilStatus();
        e.idCardFileId = p.idCardFileId();
        e.village = blankToNull(p.village());
        e.phone = blankToNull(p.phone());
        e.email = blankToNull(p.email());
        e.joinedAt = p.joinedAt();
        e.partsSocialesAmount = p.partsSocialesAmount();
        e.status = p.status();
        e.preferredPaymentMethod = blankToNull(p.preferredPaymentMethod());
        e.mobileMoneyNumber = blankToNull(p.mobileMoneyNumber());
        e.notes = blankToNull(p.notes());
    }

    private SupplierEntity createMirrorSupplier(MemberEntity m) {
        SupplierEntity s = new SupplierEntity();
        s.id = idGenerator.newId();
        s.code = m.code; // même code identifiant — facilite la traçabilité
        s.name = m.name;
        s.phone = m.phone;
        s.email = m.email;
        s.cityName = m.village;
        s.contactName = m.name;
        s.notes = "Miroir membre " + m.code + " (auto-créé)";
        s.active = true;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        s.createdBy = safeUserId();
        if (suppliers.codeExists(s.code)) {
            // Cas rare : un supplier porte déjà ce code (collision). On
            // suffixe pour préserver l'unicité.
            s.code = s.code + "-MB";
            if (suppliers.codeExists(s.code)) {
                throw new BusinessException("Impossible d'auto-créer le fournisseur miroir : code en collision.");
            }
        }
        suppliers.insert(s);
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
