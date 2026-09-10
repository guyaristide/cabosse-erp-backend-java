package com.ntech.cabosse.certification.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.certification.dto.CertificationResponseDto;
import com.ntech.cabosse.certification.dto.CertificationUpsertDto;
import com.ntech.cabosse.certification.entity.CertificationEntity;
import com.ntech.cabosse.certification.repository.CertificationRepository;
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

/**
 * Certifications du tenant : le référentiel qui remplace la saisie libre
 * des certifications de parcelle. La valeur stockée sur les documents
 * métier reste le libellé, comme le village des membres.
 */
@ApplicationScoped
public class CertificationService {

    @Inject CertificationRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<CertificationResponseDto> list() {
        return repo.listAll().stream().map(CertificationResponseDto::from).toList();
    }

    public CertificationResponseDto create(CertificationUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.crt-code-exists", code));
        }
        CertificationEntity e = new CertificationEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return CertificationResponseDto.from(e);
    }

    public CertificationResponseDto update(UUID id, CertificationUpsertDto p) {
        CertificationEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.crt-not-found", id)));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return CertificationResponseDto.from(e);
    }

    public CertificationResponseDto setActive(UUID id, boolean active) {
        CertificationEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.crt-not-found", id)));
        if (e.active == active) return CertificationResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return CertificationResponseDto.from(e);
    }

    private void auditEvt(CertificationEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("certification", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " certification « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "certification";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "certification" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
