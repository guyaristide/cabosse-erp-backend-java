package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Application <strong>manuelle</strong> des migrations Mongock à un tenant
 * unique, déclenchée par un super-admin plateforme (M9) — pendant écriture
 * du diagnostic en lecture seule de {@link TenantTechnicalService}.
 *
 * <p>Usage : reprise après échec de migration, application ciblée en debug.
 * La propagation automatique à tous les tenants au démarrage est dans
 * {@code com.ntech.cabosse.shared.startup.StartupMigrationRunner}.</p>
 */
@ApplicationScoped
public class TenantMigrationService {

    @Inject TenantRepository tenants;
    @Inject TenantMigrationRunner runner;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject Logger log;

    private String actor() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lance les migrations en attente sur la base du tenant {@code tenantId}.
     * Idempotent : un changeUnit déjà appliqué n'est pas rejoué.
     *
     * @throws NotFoundException si le tenant n'existe pas
     * @throws BusinessException si le tenant est {@link TenantStatus#DELETED}
     *         — sa base a été droppée, relancer Mongock la recréerait.
     */
    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public void runMigrationsFor(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        }
        if (tenant.status == TenantStatus.DELETED) {
            throw new BusinessException(Messages.msg("m.tnt-deleted-migrations-blocked"));
        }

        runner.runMigrationsFor(tenant.databaseName);
        log.infof("Manual migrations run for tenant %s (%s) by %s",
                tenant.slug, tenant.databaseName, actor());

        audit.event(AuditEventType.TENANT_MIGRATIONS_RUN)
                .actorEmail(actor())
                .target("tenant", tenant.id.toString(), tenant.slug)
                .tenant(tenant.id, tenant.name)
                .description("Migrations Mongock relancées manuellement (statut "
                        + tenant.status + ")")
                .record();
    }
}
