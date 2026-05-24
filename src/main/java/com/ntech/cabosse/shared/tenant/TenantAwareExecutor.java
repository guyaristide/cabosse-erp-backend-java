package com.ntech.cabosse.shared.tenant;

import com.ntech.cabosse.shared.security.Roles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.util.Set;
import java.util.UUID;

/**
 * Exécute un bloc de code dans un contexte tenant explicitement
 * réactivé. Indispensable pour :
 * <ul>
 *   <li>les jobs {@code @Scheduled},</li>
 *   <li>les consumers {@code @ConsumeEvent} qui ne propagent pas le
 *       contexte HTTP,</li>
 *   <li>tout traitement asynchrone qui doit toucher une base
 *       tenant-scoped.</li>
 * </ul>
 *
 * <p>Le {@code @ActivateRequestContext} démarre un scope request CDI,
 * permettant l'injection de beans {@link TenantContext}.</p>
 *
 * <p>L'utilisateur "système" est une identité fixe ({@code 0000…})
 * réservée aux opérations applicatives non humaines (purges, exports
 * programmés, etc.). Aucun JWT n'est émis pour cet utilisateur.</p>
 *
 * <p>Cf. multi-tenant-architecture.md §11.2.</p>
 */
@ApplicationScoped
public class TenantAwareExecutor {

    /** Identité système pour les opérations applicatives non humaines. */
    public static final UUID SYSTEM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Inject
    TenantContext tenantContext;

    /**
     * Exécute {@code task} dans un contexte tenant initialisé avec les
     * paramètres fournis et un acteur {@code SYSTEM}.
     */
    @ActivateRequestContext
    public void runForTenant(UUID tenantId, String databaseName, Runnable task) {
        runAs(tenantId, databaseName, SYSTEM_USER_ID, Set.of(Roles.SYSTEM), task);
    }

    /**
     * Variante qui permet de préciser l'utilisateur et les rôles
     * acteurs — utile quand on doit attribuer une action à un humain
     * spécifique en dehors d'un contexte HTTP (ex. : import batch
     * déclenché par un cron mais imputé à l'utilisateur qui l'a configuré).
     */
    @ActivateRequestContext
    public void runAs(UUID tenantId, String databaseName,
                      UUID userId, Set<String> roles, Runnable task) {
        tenantContext.initializeForExecutor(tenantId, databaseName, userId, roles);
        task.run();
        // Le request context se ferme automatiquement à la sortie du
        // @ActivateRequestContext.
    }

}
