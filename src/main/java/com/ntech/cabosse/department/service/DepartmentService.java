package com.ntech.cabosse.department.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.department.dto.DepartmentResponseDto;
import com.ntech.cabosse.department.dto.DepartmentUpsertDto;
import com.ntech.cabosse.department.entity.DepartmentEntity;
import com.ntech.cabosse.department.repository.DepartmentRepository;
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

/** Référentiel des départements administratifs du tenant (backlog COOP-01). */
@ApplicationScoped
public class DepartmentService {

    @Inject DepartmentRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<DepartmentResponseDto> list() {
        return repo.listAll().stream().map(DepartmentResponseDto::from).toList();
    }

    public DepartmentResponseDto create(DepartmentUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.dep-code-exists", code));
        }
        DepartmentEntity e = new DepartmentEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return DepartmentResponseDto.from(e);
    }

    public DepartmentResponseDto update(UUID id, DepartmentUpsertDto p) {
        DepartmentEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dep-not-found", id)));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return DepartmentResponseDto.from(e);
    }

    public DepartmentResponseDto setActive(UUID id, boolean active) {
        DepartmentEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dep-not-found", id)));
        if (e.active == active) return DepartmentResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return DepartmentResponseDto.from(e);
    }

    private void auditEvt(DepartmentEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("department", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " département « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "departement";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "departement" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
