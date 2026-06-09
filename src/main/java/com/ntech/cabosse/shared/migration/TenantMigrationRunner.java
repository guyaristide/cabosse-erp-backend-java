package com.ntech.cabosse.shared.migration;

import com.mongodb.client.MongoClient;
import io.mongock.driver.mongodb.sync.v4.driver.MongoSync4Driver;
import io.mongock.runner.standalone.MongockStandalone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    public void runMigrationsFor(String databaseName) {
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
