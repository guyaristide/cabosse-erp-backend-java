package com.ntech.cabosse.analytics.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.analytics.dto.CostCenterResponseDto;
import com.ntech.cabosse.analytics.dto.CostCenterUpsertDto;
import com.ntech.cabosse.analytics.entity.CostCenterEntity;
import com.ntech.cabosse.analytics.repository.CostCenterRepository;
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
 * Référentiel des centres de coût du tenant (backlog CPT-09). CRUD pur ;
 * l'imputation elle-même est portée par les lignes de pièces via le code
 * du centre, dérivé de la fiche article ou saisi sur une OD.
 */
@ApplicationScoped
public class CostCenterService {

    @Inject CostCenterRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<CostCenterResponseDto> list() {
        return repo.listAll().stream().map(CostCenterResponseDto::from).toList();
    }

    public CostCenterResponseDto create(CostCenterUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slugCode(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.ana-cost-center-code-exists", code));
        }
        CostCenterEntity e = new CostCenterEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return CostCenterResponseDto.from(e);
    }

    public CostCenterResponseDto update(UUID id, CostCenterUpsertDto p) {
        CostCenterEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ana-cost-center-not-found", id)));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return CostCenterResponseDto.from(e);
    }

    public CostCenterResponseDto setActive(UUID id, boolean active) {
        CostCenterEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ana-cost-center-not-found", id)));
        if (e.active == active) return CostCenterResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return CostCenterResponseDto.from(e);
    }

    private void apply(CostCenterEntity e, CostCenterUpsertDto p) {
        e.name = p.name().trim();
        e.description = blank(p.description());
        e.defaultProgram = blank(p.defaultProgram());
        e.defaultProject = blank(p.defaultProject());
    }

    private void auditEvt(CostCenterEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("cost_center", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " centre de coût « " + e.code + " · " + e.name + " »")
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private static String slugCode(String name) {
        if (name == null) return "CC";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "")
                .trim();
        if (n.length() > 12) n = n.substring(0, 12);
        return n.isEmpty() ? "CC" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
