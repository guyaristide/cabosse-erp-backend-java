package com.ntech.cabosse.customer.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.customer.dto.CustomerUpsertDto;
import com.ntech.cabosse.customer.entity.CustomerChannelType;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.entity.CustomerType;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class CustomerService {

    @Inject CustomerRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<CustomerResponseDto> list() {
        return repo.listAll().stream().map(CustomerResponseDto::from).toList();
    }

    public Pagination<CustomerResponseDto> page(String q, String type, PageRequest pr) {
        long total = repo.countSearch(q, type);
        List<CustomerResponseDto> items = repo.search(q, type, pr.skip(), pr.perPage()).stream()
                .map(CustomerResponseDto::from)
                .toList();
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (type != null && !type.isBlank()) filters.put("type", type.trim());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public CustomerResponseDto getById(UUID id) {
        return CustomerResponseDto.from(repo.findById(id).orElseThrow(
                () -> new NotFoundException("Client " + id + " introuvable.")));
    }

    public CustomerResponseDto create(CustomerUpsertDto p) {
        CustomerType.valueOf(p.type()); // validation
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Un client avec le code « " + code + " » existe déjà.");
        }
        CustomerEntity e = new CustomerEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.type = p.type();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return CustomerResponseDto.from(e);
    }

    public CustomerResponseDto update(UUID id, CustomerUpsertDto p) {
        CustomerEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Client " + id + " introuvable."));
        // type modifiable (un particulier peut devenir personne morale)
        e.type = p.type();
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return CustomerResponseDto.from(e);
    }

    public CustomerResponseDto setActive(UUID id, boolean active) {
        CustomerEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Client " + id + " introuvable."));
        if (e.active == active) return CustomerResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return CustomerResponseDto.from(e);
    }

    private void apply(CustomerEntity e, CustomerUpsertDto p) {
        e.name = p.name().trim();
        e.channelType = normalizeChannel(p.channelType());
        e.legalName = blank(p.legalName());
        e.taxNumber = blank(p.taxNumber());
        e.email = blank(p.email());
        e.phone = blank(p.phone());
        e.addressLine = blank(p.addressLine());
        e.cityName = blank(p.cityName());
        e.countryCode = blank(p.countryCode());
        e.contactName = blank(p.contactName());
        e.creditLimit = p.creditLimit();
        e.notes = blank(p.notes());
    }

    private void auditEvt(CustomerEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("customer", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " client « " + e.name + " »")
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    /**
     * Valide et normalise la valeur de canal. Renvoie {@code null} si
     * vide (champ optionnel) — l'export et le snapshot ventes traitent
     * {@code null} comme « non renseigné ». Lève {@link IllegalArgumentException}
     * en cas de valeur inconnue (la regex du DTO bloque déjà ce cas,
     * mais on garde la garde-fou à la frontière du domaine).
     */
    private static String normalizeChannel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return CustomerChannelType.valueOf(raw.trim()).name();
    }

    private static String slugify(String name) {
        if (name == null) return "client";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "client" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
