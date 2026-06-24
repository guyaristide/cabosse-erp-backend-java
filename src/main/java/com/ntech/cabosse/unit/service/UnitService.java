package com.ntech.cabosse.unit.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.unit.dto.UnitResponseDto;
import com.ntech.cabosse.unit.dto.UnitUpsertDto;
import com.ntech.cabosse.unit.entity.UnitEntity;
import com.ntech.cabosse.unit.repository.UnitRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class UnitService {

    @Inject UnitRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<UnitResponseDto> list() {
        return repo.listAll().stream().map(UnitResponseDto::from).toList();
    }

    public UnitResponseDto create(UnitUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Une unité avec le code « " + code + " » existe déjà.");
        }
        UnitEntity e = new UnitEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return UnitResponseDto.from(e);
    }

    public UnitResponseDto update(UUID id, UnitUpsertDto p) {
        UnitEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Unité " + id + " introuvable."));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return UnitResponseDto.from(e);
    }

    public UnitResponseDto setActive(UUID id, boolean active) {
        UnitEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Unité " + id + " introuvable."));
        if (e.active == active) return UnitResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return UnitResponseDto.from(e);
    }

    private void auditEvt(UnitEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("unit", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " unité « " + e.name + " » (" + e.code + ")")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "unite";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 12) n = n.substring(0, 12);
        return n.isEmpty() ? "unite" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
