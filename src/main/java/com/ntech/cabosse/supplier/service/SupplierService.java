package com.ntech.cabosse.supplier.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
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
import java.util.UUID;

@ApplicationScoped
public class SupplierService {

    @Inject SupplierRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<SupplierResponseDto> list() {
        return repo.listAll().stream().map(SupplierResponseDto::from).toList();
    }

    public Pagination<SupplierResponseDto> page(String q, PageRequest pr) {
        long total = repo.countSearch(q);
        List<SupplierResponseDto> items = repo.search(q, pr.skip(), pr.perPage()).stream()
                .map(SupplierResponseDto::from)
                .toList();
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public SupplierResponseDto getById(UUID id) {
        return SupplierResponseDto.from(repo.findById(id).orElseThrow(
                () -> new NotFoundException("Fournisseur " + id + " introuvable.")));
    }

    public SupplierResponseDto create(SupplierUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Un fournisseur avec le code « " + code + " » existe déjà.");
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
                () -> new NotFoundException("Fournisseur " + id + " introuvable."));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return SupplierResponseDto.from(e);
    }

    public SupplierResponseDto setActive(UUID id, boolean active) {
        SupplierEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Fournisseur " + id + " introuvable."));
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
        e.sectionId = e.collector ? p.sectionId() : null;
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
