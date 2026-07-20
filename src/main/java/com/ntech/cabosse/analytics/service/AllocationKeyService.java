package com.ntech.cabosse.analytics.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.analytics.dto.AllocationKeyResponseDto;
import com.ntech.cabosse.analytics.dto.AllocationKeyUpsertDto;
import com.ntech.cabosse.analytics.entity.AllocationKeyEntity;
import com.ntech.cabosse.analytics.repository.AllocationKeyRepository;
import com.ntech.cabosse.analytics.repository.CostCenterRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Référentiel des clés de répartition analytique (backlog CPT-17). CRUD
 * pur ; la ventilation elle-même est appliquée par le moteur comptable
 * quand une charge indirecte porte une clé.
 */
@ApplicationScoped
public class AllocationKeyService {

    @Inject AllocationKeyRepository repo;
    @Inject CostCenterRepository costCenters;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public List<AllocationKeyResponseDto> list() {
        return repo.listAll().stream().map(AllocationKeyResponseDto::from).toList();
    }

    public AllocationKeyResponseDto create(AllocationKeyUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slugCode(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Une clé de répartition avec le code « " + code + " » existe déjà.");
        }
        AllocationKeyEntity e = new AllocationKeyEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return AllocationKeyResponseDto.from(e);
    }

    public AllocationKeyResponseDto update(UUID id, AllocationKeyUpsertDto p) {
        AllocationKeyEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Clé de répartition " + id + " introuvable."));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return AllocationKeyResponseDto.from(e);
    }

    public AllocationKeyResponseDto setActive(UUID id, boolean active) {
        AllocationKeyEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Clé de répartition " + id + " introuvable."));
        if (e.active == active) return AllocationKeyResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return AllocationKeyResponseDto.from(e);
    }

    private void apply(AllocationKeyEntity e, AllocationKeyUpsertDto p) {
        e.name = p.name().trim();
        e.description = blank(p.description());
        e.method = blank(p.method());
        // Les centres de coût référencés doivent exister (n'importe quel état).
        Set<String> known = costCenters.byCode().keySet();
        e.lines = new java.util.ArrayList<>();
        for (AllocationKeyUpsertDto.Line l : p.lines()) {
            String cc = l.costCenter().trim().toUpperCase(Locale.ROOT);
            if (!known.isEmpty() && !known.contains(cc)) {
                throw new BusinessException("Centre de coût « " + cc + " » inconnu.");
            }
            e.lines.add(new AllocationKeyEntity.Line(cc, l.weight()));
        }
    }

    private void auditEvt(AllocationKeyEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("allocation_key", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " clé de répartition « " + e.code + " · " + e.name + " »")
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private static String slugCode(String name) {
        if (name == null) return "REP";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "")
                .trim();
        if (n.length() > 16) n = n.substring(0, 16);
        return n.isEmpty() ? "REP" : n;
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
