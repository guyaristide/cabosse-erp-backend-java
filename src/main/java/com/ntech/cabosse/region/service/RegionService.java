package com.ntech.cabosse.region.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.region.dto.RegionResponseDto;
import com.ntech.cabosse.region.dto.RegionUpsertDto;
import com.ntech.cabosse.region.entity.RegionEntity;
import com.ntech.cabosse.region.repository.RegionRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Référentiel des régions administratives du tenant (backlog COOP-01). */
@ApplicationScoped
public class RegionService {

    @Inject RegionRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<RegionResponseDto> list() {
        return repo.listAll().stream().map(RegionResponseDto::from).toList();
    }

    public RegionResponseDto create(RegionUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.reg-code-exists", code));
        }
        RegionEntity e = new RegionEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return RegionResponseDto.from(e);
    }

    public RegionResponseDto update(UUID id, RegionUpsertDto p) {
        RegionEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cat-region-not-found", id)));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return RegionResponseDto.from(e);
    }

    public RegionResponseDto setActive(UUID id, boolean active) {
        RegionEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cat-region-not-found", id)));
        if (e.active == active) return RegionResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return RegionResponseDto.from(e);
    }

    private void auditEvt(RegionEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("region", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " région « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "region";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "region" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
