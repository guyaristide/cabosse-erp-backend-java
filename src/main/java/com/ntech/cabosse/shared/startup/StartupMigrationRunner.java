package com.ntech.cabosse.shared.startup;

import com.mongodb.client.model.Filters;
import com.ntech.cabosse.health.MigrationHealth;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Applique les migrations Mongock en attente à tous les tenants migrables
 * <strong>à chaque démarrage de l'application</strong>
 * (cf. multi-tenant-architecture.md §10.3).
 *
 * <p>C'est le mécanisme qui propage une nouvelle migration aux tenants
 * <em>existants</em> après un déploiement. Sans lui, seul un tenant
 * fraîchement provisionné recevrait les changeUnits (le provisioning lance
 * Mongock lui-même).</p>
 *
 * <p><strong>Idempotent</strong> : chaque base tenant porte sa propre
 * collection {@code mongockChangeLog} ; un changeUnit déjà appliqué n'est
 * pas rejoué. <strong>Tolérant aux pannes</strong> : une migration qui
 * échoue sur un tenant est journalisée mais n'interrompt ni la boucle ni
 * le démarrage — les autres tenants restent opérationnels.</p>
 */
@ApplicationScoped
public class StartupMigrationRunner {

    /**
     * Statuts dont la base tenant existe et porte des données vivantes —
     * donc à migrer. On exclut volontairement :
     * <ul>
     *   <li>{@code PROVISIONING} : les migrations sont déjà lancées par le
     *       workflow de provisioning ; relancer ici créerait une course.</li>
     *   <li>{@code FAILED} : provisioning interrompu, base potentiellement
     *       partielle — réparation manuelle via l'endpoint M9.</li>
     *   <li>{@code DELETED} : base droppée ; lancer Mongock la recréerait
     *       (lock + changelog), ressuscitant un tenant supprimé.</li>
     * </ul>
     */
    private static final List<String> MIGRATABLE_STATUSES = List.of(
            TenantStatus.ACTIVE.name(),
            TenantStatus.SUSPENDED.name()
    );

    @Inject ControlPlaneProvider controlPlane;
    @Inject TenantMigrationRunner runner;
    @Inject MigrationHealth health;
    @Inject Logger log;

    /**
     * Priorité abaissée (valeur élevée) pour passer après
     * {@link ControlPlaneSchemaBootstrap} : la lecture des tenants ne dépend
     * pas des indexes du control plane, mais on garde un ordre déterministe.
     */
    void onStart(@Observes @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 600)
                 StartupEvent ev) {
        log.info("Startup tenant migrations : start");
        health.reset();
        for (TenantEntity tenant : controlPlane
                .collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
                .find(Filters.in("status", MIGRATABLE_STATUSES))) {
            try {
                runner.runMigrationsFor(tenant.databaseName);
                health.recordApplied();
                log.infof("Migrations applied for tenant %s (%s)",
                        tenant.slug, tenant.databaseName);
            } catch (Exception e) {
                // L'échec est retenu, et pas seulement journalisé : sans
                // trace exploitable, un tenant reste bloqué à la migration
                // fautive sans que rien ne le signale dans le produit.
                health.recordFailure(tenant.slug);
                log.errorf(e, "Migration failed for tenant %s (%s)",
                        tenant.slug, tenant.databaseName);
            }
        }
        log.infof("Startup tenant migrations : done (%d ok, %d failed)",
                health.applied(), health.failed());
    }
}
