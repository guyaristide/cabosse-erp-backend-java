package com.ntech.cabosse.qualitygrade.service;

import com.ntech.cabosse.qualitygrade.dto.QualityNormResponseDto;
import com.ntech.cabosse.qualitygrade.dto.QualityNormUpsertDto;
import com.ntech.cabosse.qualitygrade.entity.QualityNormEntity;
import com.ntech.cabosse.qualitygrade.repository.QualityNormRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Référentiel des seuils de qualité du tenant. */
@ApplicationScoped
public class QualityNormService {

    @Inject QualityNormRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<QualityNormResponseDto> list() {
        return repo.listAll().stream().map(QualityNormResponseDto::from).toList();
    }

    public QualityNormResponseDto create(QualityNormUpsertDto p) {
        String code = p.elementCode().trim();
        if (repo.findByElement(code).isPresent()) {
            throw new ConflictException(Messages.msg("m.qnr-element-exists", code));
        }
        ensureOrder(p);
        QualityNormEntity e = new QualityNormEntity();
        e.id = idGenerator.newId();
        e.elementCode = code;
        e.label = p.label().trim();
        e.acceptanceMaxPct = p.acceptanceMaxPct();
        e.refactionMaxPct = p.refactionMaxPct();
        e.sortOrder = p.sortOrder() != null ? p.sortOrder() : nextOrder();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return QualityNormResponseDto.from(e);
    }

    public QualityNormResponseDto update(UUID id, QualityNormUpsertDto p) {
        QualityNormEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.qnr-not-found", id)));
        ensureOrder(p);
        // Le code de l'élément n'est pas modifiable : il désigne une
        // colonne d'analyse, pas un libellé.
        e.label = p.label().trim();
        e.acceptanceMaxPct = p.acceptanceMaxPct();
        e.refactionMaxPct = p.refactionMaxPct();
        if (p.sortOrder() != null) e.sortOrder = p.sortOrder();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return QualityNormResponseDto.from(e);
    }

    public QualityNormResponseDto setActive(UUID id, boolean active) {
        QualityNormEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.qnr-not-found", id)));
        if (e.active == active) return QualityNormResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return QualityNormResponseDto.from(e);
    }

    /**
     * Le seuil de réfaction se situe au-delà du seuil d'acceptation.
     *
     * <p>L'inverse décrirait une fourchette qui commence après sa fin :
     * l'écran afficherait « accepté jusqu'à 9 %, réfaction jusqu'à 8 % »,
     * ce qui ne se lit pas.</p>
     */
    private static void ensureOrder(QualityNormUpsertDto p) {
        if (p.acceptanceMaxPct() == null || p.refactionMaxPct() == null) return;
        if (p.refactionMaxPct().compareTo(p.acceptanceMaxPct()) < 0) {
            throw new BusinessException(Messages.msg("m.qnr-refaction-below-acceptance"));
        }
    }

    private int nextOrder() {
        return repo.listAll().stream().mapToInt(n -> n.sortOrder).max().orElse(0) + 10;
    }

    private void auditEvt(QualityNormEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("quality-norm", e.id.toString(), e.label)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " seuil « " + e.label + " »")
                .record();
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    private static String norm(String s) { return s == null ? null : s.trim().toLowerCase(Locale.ROOT); }
}
