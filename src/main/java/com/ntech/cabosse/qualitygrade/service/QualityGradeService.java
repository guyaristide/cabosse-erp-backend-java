package com.ntech.cabosse.qualitygrade.service;

import com.ntech.cabosse.qualitygrade.dto.QualityGradeResponseDto;
import com.ntech.cabosse.qualitygrade.dto.QualityGradeUpsertDto;
import com.ntech.cabosse.qualitygrade.entity.QualityGradeEntity;
import com.ntech.cabosse.qualitygrade.repository.QualityGradeRepository;
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

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Référentiel des grades de qualité du tenant.
 *
 * <p>Sans seed : chaque filière a sa nomenclature. Une liste figée dans le
 * code aurait obligé l'hévéa et l'anacarde à parler cacao.</p>
 */
@ApplicationScoped
public class QualityGradeService {

    @Inject QualityGradeRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<QualityGradeResponseDto> list() {
        return repo.listAll().stream().map(QualityGradeResponseDto::from).toList();
    }

    /**
     * Vérifie qu'un code de grade existe et est actif.
     *
     * <p>Appelé par le contrôle qualité et par la grille tarifaire d'une
     * campagne : les deux classaient jusqu'ici sur deux nomenclatures
     * distinctes, dont aucune n'était vérifiée. Un code inconnu passait
     * donc sans bruit, et la prime attachée ne trouvait jamais son
     * grade.</p>
     *
     * @return le code tel qu'il est enregistré au référentiel, ou null si
     *         rien n'était demandé
     */
    public String requireCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        QualityGradeEntity found = repo.findByCode(raw.trim()).orElseThrow(
                () -> new BusinessException(Messages.msg("m.qgr-unknown", raw.trim())));
        if (!found.active) {
            throw new BusinessException(Messages.msg("m.qgr-inactive", found.code));
        }
        return found.code;
    }

    public QualityGradeResponseDto create(QualityGradeUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT)
                : slugify(p.label());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.qgr-code-exists", code));
        }
        QualityGradeEntity e = new QualityGradeEntity();
        e.id = idGenerator.newId();
        e.code = code;
        e.label = p.label().trim();
        e.sortOrder = p.sortOrder() != null ? p.sortOrder() : nextOrder();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return QualityGradeResponseDto.from(e);
    }

    public QualityGradeResponseDto update(UUID id, QualityGradeUpsertDto p) {
        QualityGradeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.qgr-not-found", id)));
        // Le code n'est pas modifiable : des contrôles qualité et des primes
        // de campagne le référencent, et le renommer les orphelinerait sans
        // que rien ne le signale.
        e.label = p.label().trim();
        if (p.sortOrder() != null) e.sortOrder = p.sortOrder();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return QualityGradeResponseDto.from(e);
    }

    public QualityGradeResponseDto setActive(UUID id, boolean active) {
        QualityGradeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.qgr-not-found", id)));
        if (e.active == active) return QualityGradeResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return QualityGradeResponseDto.from(e);
    }

    /** Le nouveau grade se range en fin de liste, faute de mieux savoir. */
    private int nextOrder() {
        return repo.listAll().stream().mapToInt(g -> g.sortOrder).max().orElse(0) + 10;
    }

    private void auditEvt(QualityGradeEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("quality-grade", e.id.toString(), e.label)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " grade « " + e.label + " »")
                .record();
    }

    private static String slugify(String label) {
        if (label == null) return "GRADE";
        String n = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 20) n = n.substring(0, 20);
        return n.isEmpty() ? "GRADE" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
