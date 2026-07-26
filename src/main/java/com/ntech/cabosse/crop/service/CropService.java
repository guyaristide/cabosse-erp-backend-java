package com.ntech.cabosse.crop.service;

import com.ntech.cabosse.crop.dto.CropResponseDto;
import com.ntech.cabosse.crop.dto.CropUpsertDto;
import com.ntech.cabosse.crop.entity.CropEntity;
import com.ntech.cabosse.crop.repository.CropRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Référentiel des cultures du tenant (backlog PARC-02). */
@ApplicationScoped
public class CropService {

    @Inject CropRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<CropResponseDto> list() {
        return repo.listAll().stream().map(CropResponseDto::from).toList();
    }

    /** Libellés par code, pour dénormaliser sans requête par ligne. */
    public Map<String, String> namesByCode() {
        return repo.listAll().stream()
                .filter(c -> c.code != null)
                .collect(Collectors.toMap(c -> c.code, c -> c.name, (a, b) -> a));
    }

    public CropResponseDto create(CropUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Une culture avec le code « " + code + " » existe déjà.");
        }
        CropEntity e = new CropEntity();
        e.id = idGenerator.newId();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return CropResponseDto.from(e);
    }

    public CropResponseDto update(UUID id, CropUpsertDto p) {
        CropEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Culture " + id + " introuvable."));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return CropResponseDto.from(e);
    }

    public CropResponseDto setActive(UUID id, boolean active) {
        CropEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Culture " + id + " introuvable."));
        if (e.active == active) return CropResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return CropResponseDto.from(e);
    }

    private void auditEvt(CropEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("crop", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " culture « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "culture";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "culture" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    /** Exposé pour les services qui résolvent un libellé unitaire. */
    public Function<String, String> nameResolver() {
        Map<String, String> names = namesByCode();
        return code -> code == null ? null : names.getOrDefault(code, code);
    }
}
