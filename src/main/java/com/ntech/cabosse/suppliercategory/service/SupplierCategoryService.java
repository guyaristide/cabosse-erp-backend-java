package com.ntech.cabosse.suppliercategory.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.suppliercategory.dto.SupplierCategoryDtos;
import com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity;
import com.ntech.cabosse.suppliercategory.repository.SupplierCategoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Référentiel des catégories de fournisseur du tenant (backlog ACH-07).
 *
 * <p>Toute modification de rémunération est journalisée avec sa valeur
 * précédente : changer un taux en cours de campagne déplace de l'argent,
 * et la question « depuis quand ce taux ? » doit trouver une réponse
 * ailleurs que dans la mémoire de celui qui l'a saisi.</p>
 */
@ApplicationScoped
public class SupplierCategoryService {

    @Inject SupplierCategoryRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public List<SupplierCategoryDtos.ResponseDto> list() {
        return repo.listAll().stream().map(SupplierCategoryDtos.ResponseDto::from).toList();
    }

    public SupplierCategoryDtos.ResponseDto getById(UUID id) {
        return SupplierCategoryDtos.ResponseDto.from(loadOrFail(id));
    }

    public SupplierCategoryDtos.ResponseDto create(SupplierCategoryDtos.UpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slug(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.suc-code-exists", code));
        }
        SupplierCategoryEntity e = new SupplierCategoryEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        audit(e, "Création catégorie fournisseur « " + e.code + " · " + e.name + " »"
                + marginSuffix(e));
        return SupplierCategoryDtos.ResponseDto.from(e);
    }

    public SupplierCategoryDtos.ResponseDto update(UUID id, SupplierCategoryDtos.UpsertDto p) {
        SupplierCategoryEntity e = loadOrFail(id);
        String previousMode = e.marginMode;
        BigDecimal previousRate = e.marginRate;
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);

        boolean marginChanged = !java.util.Objects.equals(previousMode, e.marginMode)
                || compare(previousRate, e.marginRate) != 0;
        audit(e, marginChanged
                ? "Rémunération de la catégorie « " + e.name + " » : "
                        + describe(previousMode, previousRate) + " vers " + describe(e.marginMode, e.marginRate)
                : "Modification catégorie fournisseur « " + e.code + " · " + e.name + " »");
        return SupplierCategoryDtos.ResponseDto.from(e);
    }

    public SupplierCategoryDtos.ResponseDto setActive(UUID id, boolean active) {
        SupplierCategoryEntity e = loadOrFail(id);
        if (e.active == active) return SupplierCategoryDtos.ResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        audit(e, (active ? "Réactivation" : "Désactivation")
                + " catégorie fournisseur « " + e.code + " · " + e.name + " »");
        return SupplierCategoryDtos.ResponseDto.from(e);
    }

    // ─── Internes ───────────────────────────────────────────────────

    private void apply(SupplierCategoryEntity e, SupplierCategoryDtos.UpsertDto p) {
        e.name = p.name().trim();
        e.description = blankToNull(p.description());
        e.marginMode = blankToNull(p.marginMode());
        // Un taux sans mode ne veut rien dire, et un mode sans taux non
        // plus : les deux se posent ou se laissent hériter ensemble.
        e.marginRate = e.marginMode == null ? null : p.marginRate();
    }

    private SupplierCategoryEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.suc-not-found", id)));
    }

    private static String describe(String mode, BigDecimal rate) {
        if (mode == null) return "réglage du tenant";
        return switch (mode) {
            case "PER_KG" -> nz(rate) + " par kg";
            case "PERCENT" -> nz(rate) + " %";
            default -> "aucune rémunération";
        };
    }

    private static String marginSuffix(SupplierCategoryEntity e) {
        return e.marginMode == null ? "" : " · " + describe(e.marginMode, e.marginRate);
    }

    private static int compare(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null || b == null) return 1;
        return a.compareTo(b);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private void audit(SupplierCategoryEntity e, String description) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("supplier_category", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private static String slug(String name) {
        if (name == null) return "CAT";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "").trim();
        if (n.length() > 16) n = n.substring(0, 16);
        return n.isEmpty() ? "CAT" : n;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
