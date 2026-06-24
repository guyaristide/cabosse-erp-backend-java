package com.ntech.cabosse.variety.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.variety.dto.VarietyResponseDto;
import com.ntech.cabosse.variety.dto.VarietyUpsertDto;
import com.ntech.cabosse.variety.entity.VarietyEntity;
import com.ntech.cabosse.variety.repository.VarietyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class VarietyService {

    @Inject VarietyRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<VarietyResponseDto> list() {
        return repo.listAll().stream().map(VarietyResponseDto::from).toList();
    }

    public VarietyResponseDto create(VarietyUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Une variété avec le code « " + code + " » existe déjà.");
        }
        VarietyEntity e = new VarietyEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return VarietyResponseDto.from(e);
    }

    public VarietyResponseDto update(UUID id, VarietyUpsertDto p) {
        VarietyEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Variété " + id + " introuvable."));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return VarietyResponseDto.from(e);
    }

    public VarietyResponseDto setActive(UUID id, boolean active) {
        VarietyEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Variété " + id + " introuvable."));
        if (e.active == active) return VarietyResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return VarietyResponseDto.from(e);
    }

    private void auditEvt(VarietyEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("variety", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " variété « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "variete";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "variete" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
