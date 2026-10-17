package com.ntech.cabosse.shared.migration;

import com.mongodb.client.MongoClient;
import io.mongock.driver.mongodb.sync.v4.driver.MongoSync4Driver;
import io.mongock.runner.standalone.MongockStandalone;
import jakarta.enterprise.context.ApplicationScoped;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Lanceur Mongock pour une base tenant donnée. Chaque base tenant porte
 * sa propre collection {@code mongockChangeLog} qui trace les migrations
 * appliquées (cf. multi-tenant-architecture.md §10.2).
 *
 * <p>Une migration ratée sur un tenant n'impacte pas les autres :
 * Mongock fonctionne base par base, le contexte d'exécution est strict.</p>
 *
 * <p>Les migrations elles-mêmes vivent sous le package
 * {@value #MIGRATION_PACKAGE}, structurées par ordre numérique
 * {@code MNNN_*}.</p>
 */
@ApplicationScoped
public class TenantMigrationRunner {

    public static final String MIGRATION_PACKAGE = "com.ntech.cabosse.migrations";

    @Inject
    MongoClient mongoClient;

    /**
     * Applique toutes les migrations en attente sur la base spécifiée.
     *
     * <p>Le {@link MongoClient} est exposé en dépendance Mongock pour que
     * les changeUnits conditionnelles (cf.
     * {@code com.ntech.cabosse.shared.migration.CapabilityMigrationGuard})
     * puissent l'injecter et interroger le control plane afin de vérifier
     * les capacités du tenant avant exécution.</p>
     */
    @Inject Logger log;

    public void runMigrationsFor(String databaseName) {
        // Le plan de contrôle n'est pas une base de structure : y rejouer
        // les migrations tenant y crée des collections homonymes (regions,
        // cloud_files) et met en péril les catalogues globaux. Le tenant
        // technique de la plateforme pointe historiquement dessus, d'où
        // ce refus ici, au seul endroit par où passent tous les appelants.
        if (ControlPlane.DATABASE.equals(databaseName)) {
            log.warnf("Tenant migrations refused on the control plane database %s", databaseName);
            return;
        }
        MongoSync4Driver driver = MongoSync4Driver.withDefaultLock(
                mongoClient, databaseName);

        MongockStandalone.builder()
                .setDriver(driver)
                .addDependency(MongoClient.class, mongoClient)
                .addMigrationScanPackage(MIGRATION_PACKAGE)
                .setTrackIgnored(true)
                .buildRunner()
                .execute();
    }
}
