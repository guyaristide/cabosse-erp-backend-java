package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Couper ou rendre l'accès à une structure, à la main.
 *
 * <p>Le bouton existait au back-office depuis l'origine sans rien faire,
 * et les deux types d'audit étaient déclarés sans jamais être émis : la
 * seule suspension possible était celle, automatique, d'une licence
 * échue (relevé le 04/10/2026).</p>
 *
 * <p>Suspendre est une décision commerciale, pas une sanction technique.
 * Elle s'écrit au journal avec son motif, parce que la structure
 * demandera pourquoi, et qu'une coupure sans trace devient une coupure
 * que personne n'assume.</p>
 */
@ApplicationScoped
public class TenantSuspensionService {

    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject Logger log;

    public TenantEntity suspend(UUID tenantId, String reason) {
        TenantEntity tenant = load(tenantId);
        if (tenant.status == TenantStatus.SUSPENDED) {
            throw new BusinessException(Messages.msg("m.tnt-already-suspended", tenant.name));
        }
        if (tenant.status != TenantStatus.ACTIVE) {
            throw new BusinessException(Messages.msg("m.tnt-not-operational", tenant.status));
        }
        tenant.status = TenantStatus.SUSPENDED;
        tenant.suspendedAt = Instant.now();
        tenant.updatedAt = tenant.suspendedAt;
        tenants.update(tenant);

        audit.event(AuditEventType.TENANT_SUSPENDED)
                .actorEmail(actor())
                .target("tenant", tenant.id.toString(), tenant.slug)
                .tenant(tenant.id, tenant.name)
                .description(reason == null || reason.isBlank()
                        ? "Accès suspendu" : "Accès suspendu : " + reason.trim())
                .record();
        log.warnf("Tenant %s suspendu par %s", tenant.slug, actor());
        return tenant;
    }

    /**
     * Rend l'accès.
     *
     * <p>La licence n'est pas examinée : une structure peut être remise
     * en service le temps d'un échange commercial, avant même que son
     * renouvellement ne soit signé. Le balayage quotidien la
     * resuspendra si rien n'a bougé, et c'est très bien ainsi : il dira
     * alors que la décision n'a pas été suivie d'effet.</p>
     */
    public TenantEntity reactivate(UUID tenantId) {
        TenantEntity tenant = load(tenantId);
        if (tenant.status == TenantStatus.ACTIVE) {
            throw new BusinessException(Messages.msg("m.tnt-already-active", tenant.name));
        }
        if (tenant.status != TenantStatus.SUSPENDED) {
            throw new BusinessException(Messages.msg("m.tnt-not-operational", tenant.status));
        }
        tenant.status = TenantStatus.ACTIVE;
        tenant.suspendedAt = null;
        tenant.updatedAt = Instant.now();
        if (tenant.subscription != null) {
            tenant.subscription.suspendedAt = null;
        }
        tenants.update(tenant);

        audit.event(AuditEventType.TENANT_REACTIVATED)
                .actorEmail(actor())
                .target("tenant", tenant.id.toString(), tenant.slug)
                .tenant(tenant.id, tenant.name)
                .description("Accès rendu")
                .record();
        log.infof("Tenant %s réactivé par %s", tenant.slug, actor());
        return tenant;
    }

    private TenantEntity load(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        }
        return tenant;
    }

    private String actor() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
