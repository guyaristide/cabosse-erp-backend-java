package com.ntech.cabosse.supplier.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierDuplicateDto;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class SupplierService {

    @Inject com.ntech.cabosse.members.repository.MemberRepository members;

    @Inject SupplierRepository repo;
    @Inject com.ntech.cabosse.locality.repository.LocalityRepository localities;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject com.ntech.cabosse.suppliercategory.repository.SupplierCategoryRepository categories;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<SupplierResponseDto> list() {
        var refs = categories.byId();
        return repo.listAll().stream().map(e -> SupplierResponseDto.from(e, categoryName(refs, e))).toList();
    }

    public Pagination<SupplierResponseDto> page(String q, PageRequest pr) {
        long total = repo.countSearch(q);
        var refs = categories.byId();
        List<SupplierResponseDto> items = repo.search(q, pr.skip(), pr.perPage()).stream()
                .map(e -> SupplierResponseDto.from(e, categoryName(refs, e)))
                .toList();
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public SupplierResponseDto getById(UUID id) {
        SupplierEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.sup-not-found", id)));
        return SupplierResponseDto.from(e, categoryName(categories.byId(), e));
    }

    private static String categoryName(
            java.util.Map<UUID, com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity> refs,
            SupplierEntity e) {
        if (e.categoryId == null) return null;
        var ref = refs.get(e.categoryId);
        return ref != null ? ref.name : null;
    }

    @Inject SupplierDuplicateDetector duplicates;

    /**
     * Fournisseurs déjà enregistrés proches de cette identité (EF-03).
     * Consultée à la saisie, et de nouveau à la création pour que la règle
     * ne dépende pas de l'écran qui appelle.
     */
    public List<SupplierDuplicateDto> findDuplicates(String name, String phone,
                                                     String cityName, UUID excludeId) {
        return duplicates.search(name, phone, cityName, excludeId);
    }

    /**
     * Création sans contrôle de doublon. Réservée aux flux qui font leur
     * propre rapprochement (imports de masse, miroir d'un producteur) :
     * leur imposer une confirmation interactive n'aurait pas de sens.
     */
    public SupplierResponseDto create(SupplierUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.sup-code-exists", code));
        }
        SupplierEntity e = new SupplierEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return SupplierResponseDto.from(e);
    }

    public SupplierResponseDto update(UUID id, SupplierUpsertDto p) {
        SupplierEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.sup-not-found", id)));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        syncMirroredMember(e);
        return SupplierResponseDto.from(e);
    }

    /**
     * Renvoie la qualité de délégué vers la fiche du producteur quand ce
     * fournisseur est son miroir. Sans ce retour, une case décochée ici
     * laisserait la fiche du producteur affirmer le contraire, et la
     * question « qui est délégué ? » aurait deux réponses.
     */
    private void syncMirroredMember(SupplierEntity e) {
        members.findBySupplierId(e.id).ifPresent(m -> {
            if (m.collector == e.collector
                    && java.util.Objects.equals(m.collectorMarginRate, e.collectorMarginRate)) {
                return;
            }
            m.collector = e.collector;
            m.collectorMarginRate = e.collectorMarginRate;
            m.updatedAt = Instant.now();
            members.replace(m);
        });
    }

    public SupplierResponseDto setActive(UUID id, boolean active) {
        SupplierEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.sup-not-found", id)));
        if (e.active == active) return SupplierResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return SupplierResponseDto.from(e);
    }

    private void apply(SupplierEntity e, SupplierUpsertDto p) {
        e.name = p.name().trim();
        e.legalName = blank(p.legalName());
        e.taxNumber = blank(p.taxNumber());
        e.email = blank(p.email());
        e.phone = blank(p.phone());
        e.addressLine = blank(p.addressLine());
        e.cityName = blank(p.cityName());
        e.countryCode = blank(p.countryCode());
        e.contactName = blank(p.contactName());
        e.paymentTerms = blank(p.paymentTerms());
        e.notes = blank(p.notes());
        e.collector = p.collector() != null && p.collector();
        e.localityIds = e.collector ? distinctIds(p.localityIds()) : new java.util.ArrayList<>();
        ensureLocalitiesAreFree(e);
        // La section se dérive des localités dès qu'il y en a : c'est la
        // localité qui porte le rattachement, la section n'en étant que le
        // regroupement. Sans localité, on garde la section saisie, pour les
        // structures qui n'ont pas encore rangé leurs villages.
        UUID derived = derivedSection(e.localityIds);
        e.sectionId = e.collector ? (derived != null ? derived : p.sectionId()) : null;
        e.collectorMarginRate = e.collector ? p.collectorMarginRate() : null;
        e.collectorRetentionPerKgFcfa = e.collector ? p.collectorRetentionPerKgFcfa() : null;
        // La catégorie classe le fournisseur quelle que soit sa qualité :
        // un planteur qui livre en direct en a une comme un délégué.
        if (p.categoryId() != null && categories.findById(p.categoryId()).isEmpty()) {
            throw new NotFoundException(Messages.msg("m.suc-not-found", p.categoryId()));
        }
        e.categoryId = p.categoryId();
    }

    private static java.util.List<UUID> distinctIds(java.util.List<UUID> raw) {
        if (raw == null) return new java.util.ArrayList<>();
        return raw.stream().filter(java.util.Objects::nonNull).distinct()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * La section commune aux localités du délégué.
     *
     * <p>Null si elles relèvent de sections différentes ou d'aucune : un
     * délégué qui travaille de part et d'autre d'une frontière de section
     * n'appartient à aucune, et forcer l'une des deux mentirait sur les
     * états qui trient par section.</p>
     */
    private UUID derivedSection(java.util.List<UUID> localityIds) {
        if (localityIds == null || localityIds.isEmpty()) return null;
        java.util.Set<UUID> found = new java.util.HashSet<>();
        for (UUID id : localityIds) {
            localities.findById(id).ifPresent(l -> {
                if (l.sectionId != null) found.add(l.sectionId);
            });
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * Une localité est gérée par un seul délégué.
     *
     * <p>Deux délégués sur le même village compteraient deux fois la même
     * collecte et rendraient indécidable à qui rattacher un producteur.</p>
     */
    private void ensureLocalitiesAreFree(SupplierEntity e) {
        if (e.localityIds == null || e.localityIds.isEmpty()) return;
        var conflicts = repo.findCoveringAnyLocality(e.localityIds, e.id);
        if (conflicts.isEmpty()) return;
        SupplierEntity other = conflicts.get(0);
        String village = e.localityIds.stream()
                .filter(id -> other.localityIds != null && other.localityIds.contains(id))
                .findFirst()
                .flatMap(localities::findById)
                .map(l -> l.name)
                .orElse("?");
        throw new ConflictException(Messages.msg("m.sup-locality-taken", village, other.name));
    }

    private void auditEvt(SupplierEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("supplier", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " fournisseur « " + e.name + " »")
                .record();
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String slugify(String name) {
        if (name == null) return "fournisseur";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "fournisseur" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
