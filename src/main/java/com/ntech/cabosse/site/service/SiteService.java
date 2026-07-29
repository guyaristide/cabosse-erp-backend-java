package com.ntech.cabosse.site.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.dto.SiteResponseDto;
import com.ntech.cabosse.site.dto.SiteUpsertDto;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.entity.SiteType;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lecture et écriture des sites du tenant courant.
 *
 * <p>Lecture : ouverte à tout user authentifié du tenant. Écriture :
 * {@code TENANT_ADMIN} (gating au niveau de la resource).</p>
 *
 * <p>Quota plan : à chaque création on lit le {@code maxSites} du plan
 * du tenant et on vérifie qu'on ne dépasse pas. Au MVP le quota
 * s'applique à tous les types confondus.</p>
 */
@ApplicationScoped
public class SiteService {

    @Inject SiteRepository sites;
    @Inject TenantRepository tenants;
    @Inject PlanRepository plans;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject Logger log;

    private String currentActorEmail() {
        try { return jwt != null ? jwt.getName() : null; } catch (Exception e) { return null; }
    }

    // ─── Lecture ─────────────────────────────────────────────────────

    public List<SiteResponseDto> list(SiteType type) {
        List<SiteEntity> entities = (type == null) ? sites.listAll() : sites.listByType(type);
        return entities.stream().map(SiteResponseDto::from).toList();
    }

    public SiteResponseDto getById(UUID id) {
        SiteEntity e = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        return SiteResponseDto.from(e);
    }

    // ─── Écriture ────────────────────────────────────────────────────

    public SiteResponseDto create(SiteUpsertDto payload) {
        if (payload.type() == null || payload.type().isBlank()) {
            throw new BusinessException("Type requis à la création (TRANSFORMATION | SALES_POINT "
                    + "| SECTION_WAREHOUSE | CENTRAL_WAREHOUSE).");
        }
        SiteType type = SiteType.valueOf(payload.type());
        assertWithinQuota();

        String code = (payload.code() != null && !payload.code().isBlank())
                ? payload.code().trim()
                : slugify(payload.name());
        if (sites.codeExists(code)) {
            throw new ConflictException("Un site avec le code « " + code + " » existe déjà.");
        }

        SiteEntity entity = new SiteEntity();
        entity.id = UuidCreator.getTimeOrderedEpoch();
        entity.type = type.name();
        entity.code = code;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        entity.createdBy = safeUserId();
        applyMutableFields(entity, payload);
        sites.insert(entity);

        TenantEntity tenant = tenantOrNull();
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(currentActorEmail())
                .target("site", entity.id.toString(), entity.name)
                .tenant(tenant != null ? tenant.id : null, tenant != null ? tenant.name : null)
                .description("Création du site « " + entity.name + " » (" + entity.type + ")")
                .record();

        return SiteResponseDto.from(entity);
    }

    public SiteResponseDto update(UUID id, SiteUpsertDto payload) {
        SiteEntity entity = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        // Type et code sont immutables — on ignore ces deux champs en update.
        applyMutableFields(entity, payload);
        entity.updatedAt = Instant.now();
        sites.replace(entity);

        TenantEntity tenant = tenantOrNull();
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(currentActorEmail())
                .target("site", entity.id.toString(), entity.name)
                .tenant(tenant != null ? tenant.id : null, tenant != null ? tenant.name : null)
                .description("Modification du site « " + entity.name + " »")
                .record();

        return SiteResponseDto.from(entity);
    }

    public SiteResponseDto setActive(UUID id, boolean active) {
        SiteEntity entity = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        if (entity.active == active) return SiteResponseDto.from(entity);
        sites.updateActive(id, active);
        entity.active = active;
        entity.updatedAt = Instant.now();

        TenantEntity tenant = tenantOrNull();
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(currentActorEmail())
                .target("site", entity.id.toString(), entity.name)
                .tenant(tenant != null ? tenant.id : null, tenant != null ? tenant.name : null)
                .description((active ? "Réactivation" : "Désactivation") + " du site « " + entity.name + " »")
                .record();

        return SiteResponseDto.from(entity);
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static void applyMutableFields(SiteEntity entity, SiteUpsertDto p) {
        entity.name = p.name().trim();
        entity.addressLine = blankToNull(p.addressLine());
        entity.cityId = blankToNull(p.cityId());
        entity.cityName = blankToNull(p.cityName());
        entity.regionCode = blankToNull(p.regionCode());
        entity.countryCode = blankToNull(p.countryCode());
        entity.latitude = p.latitude();
        entity.longitude = p.longitude();
        entity.phone = blankToNull(p.phone());
        entity.email = blankToNull(p.email());
        entity.managerName = blankToNull(p.managerName());
        entity.description = blankToNull(p.description());
        entity.openingHours = blankToNull(p.openingHours());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Slugify simple — accent stripping + lowercase + non-[a-z0-9] → tirets.
     * Pas de garantie d'unicité ; l'appelant vérifie via {@code codeExists}.
     */
    private static String slugify(String name) {
        if (name == null) return "site";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        if (n.isEmpty()) n = "site";
        return n;
    }

    private void assertWithinQuota() {
        TenantEntity tenant = tenantOrNull();
        if (tenant == null) return; // ne devrait pas arriver en flow normal
        PlanEntity plan = plans.findByCode(tenant.planCode).orElse(null);
        if (plan == null) return; // plan inconnu → on ne bloque pas, l'admin verra
        long current = sites.count();
        if (current >= plan.maxSites) {
            throw new BusinessException(
                    "Quota atteint : votre plan « " + plan.name + " » autorise "
                            + plan.maxSites + " sites. Mettez à niveau votre abonnement pour en ajouter."
            );
        }
    }

    private TenantEntity tenantOrNull() {
        try { return tenants.findById(tenantContext.tenantId()); }
        catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
