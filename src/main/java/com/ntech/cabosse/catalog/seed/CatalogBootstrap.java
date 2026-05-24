package com.ntech.cabosse.catalog.seed;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Observer {@link StartupEvent} qui amorce les catalogues partagés au
 * démarrage de l'app.
 *
 * <p>Priorité {@code 2550} : s'exécute après
 * {@link com.ntech.cabosse.shared.startup.ControlPlaneSchemaBootstrap}
 * (priorité par défaut 2500) — les indexes doivent exister avant l'insert
 * — et avant
 * {@link com.ntech.cabosse.auth.bootstrap.PlatformAdminBootstrap} (2600)
 * par convention.</p>
 *
 * <p>Skip total en mode {@code TEST} : les tests appellent
 * {@link CatalogSeeder#seedAll()} eux-mêmes depuis
 * {@code AbstractIntegrationTest.resetState()} après avoir droppé la DB.</p>
 */
@ApplicationScoped
public class CatalogBootstrap {

    @Inject CatalogSeeder seeder;
    @Inject Logger log;

    void onStart(@Observes @Priority(2550) StartupEvent ev) {
        if (LaunchMode.current() == LaunchMode.TEST) return;
        log.info("Catalog bootstrap — start");
        seeder.seedAll();
        log.info("Catalog bootstrap — OK");
    }
}
