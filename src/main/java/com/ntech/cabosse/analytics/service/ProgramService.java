package com.ntech.cabosse.analytics.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.analytics.dto.ProgramResponseDto;
import com.ntech.cabosse.analytics.dto.ProgramUpsertDto;
import com.ntech.cabosse.analytics.entity.ProgramEntity;
import com.ntech.cabosse.analytics.repository.ProgramRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Référentiel des programmes budgétaires du tenant (backlog CPT-10). CRUD
 * pur, projets imbriqués. L'imputation elle-même est portée par les
 * lignes de pièces (codes programme + projet), dérivée du centre de coût
 * (charges) ou de l'article vendu (produits).
 */
@ApplicationScoped
public class ProgramService {

    @Inject ProgramRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<ProgramResponseDto> list() {
        return repo.listAll().stream().map(ProgramResponseDto::from).toList();
    }

    public ProgramResponseDto create(ProgramUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slugCode(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Un programme avec le code « " + code + " » existe déjà.");
        }
        ProgramEntity e = new ProgramEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return ProgramResponseDto.from(e);
    }

    public ProgramResponseDto update(UUID id, ProgramUpsertDto p) {
        ProgramEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Programme " + id + " introuvable."));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return ProgramResponseDto.from(e);
    }

    public ProgramResponseDto setActive(UUID id, boolean active) {
        ProgramEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Programme " + id + " introuvable."));
        if (e.active == active) return ProgramResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return ProgramResponseDto.from(e);
    }

    private void apply(ProgramEntity e, ProgramUpsertDto p) {
        e.name = p.name().trim();
        e.description = blank(p.description());
        List<ProgramEntity.Project> projects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (p.projects() != null) {
            for (ProgramUpsertDto.ProjectPayload pp : p.projects()) {
                if (pp == null || pp.name() == null || pp.name().isBlank()) continue;
                String pcode = (pp.code() != null && !pp.code().isBlank())
                        ? pp.code().trim().toUpperCase(Locale.ROOT) : slugCode(pp.name());
                if (!seen.add(pcode)) {
                    throw new BusinessException("Code projet en double dans le programme : « " + pcode + " ».");
                }
                ProgramEntity.Project proj = new ProgramEntity.Project();
                proj.code = pcode;
                proj.name = pp.name().trim();
                proj.active = pp.active() == null || pp.active();
                projects.add(proj);
            }
        }
        e.projects = projects;
    }

    private void auditEvt(ProgramEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("program", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " programme « " + e.code + " · " + e.name + " »")
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private static String slugCode(String name) {
        if (name == null) return "PROG";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "")
                .trim();
        if (n.length() > 16) n = n.substring(0, 16);
        return n.isEmpty() ? "PROG" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
