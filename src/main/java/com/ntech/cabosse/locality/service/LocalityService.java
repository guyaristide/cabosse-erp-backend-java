package com.ntech.cabosse.locality.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.locality.dto.LocalityResponseDto;
import com.ntech.cabosse.locality.dto.LocalityUpsertDto;
import com.ntech.cabosse.locality.entity.LocalityEntity;
import com.ntech.cabosse.locality.repository.LocalityRepository;
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
public class LocalityService {

    @Inject LocalityRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<LocalityResponseDto> list() {
        return repo.listAll().stream().map(LocalityResponseDto::from).toList();
    }

    public LocalityResponseDto create(LocalityUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Une localité avec le code « " + code + " » existe déjà.");
        }
        LocalityEntity e = new LocalityEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return LocalityResponseDto.from(e);
    }

    public LocalityResponseDto update(UUID id, LocalityUpsertDto p) {
        LocalityEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Localité " + id + " introuvable."));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return LocalityResponseDto.from(e);
    }

    public LocalityResponseDto setActive(UUID id, boolean active) {
        LocalityEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Localité " + id + " introuvable."));
        if (e.active == active) return LocalityResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return LocalityResponseDto.from(e);
    }

    private void auditEvt(LocalityEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("locality", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " localité « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "localite";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "localite" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
