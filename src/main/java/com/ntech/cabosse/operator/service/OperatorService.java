package com.ntech.cabosse.operator.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.operator.dto.OperatorResponseDto;
import com.ntech.cabosse.operator.dto.OperatorUpsertDto;
import com.ntech.cabosse.operator.entity.OperatorEntity;
import com.ntech.cabosse.operator.repository.OperatorRepository;
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

@ApplicationScoped
public class OperatorService {

    @Inject OperatorRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<OperatorResponseDto> list() {
        return repo.listAll().stream().map(OperatorResponseDto::from).toList();
    }

    public OperatorResponseDto create(OperatorUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.ope-code-exists", code));
        }
        OperatorEntity e = new OperatorEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return OperatorResponseDto.from(e);
    }

    public OperatorResponseDto update(UUID id, OperatorUpsertDto p) {
        OperatorEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ope-not-found", id)));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return OperatorResponseDto.from(e);
    }

    public OperatorResponseDto setActive(UUID id, boolean active) {
        OperatorEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ope-not-found", id)));
        if (e.active == active) return OperatorResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return OperatorResponseDto.from(e);
    }

    private void auditEvt(OperatorEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("operator", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " opérateur « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "operateur";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "operateur" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
