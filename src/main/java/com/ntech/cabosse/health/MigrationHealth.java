package com.ntech.cabosse.health;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ce qu'a donné la passe de migrations du dernier démarrage.
 *
 * <p>Une migration qui échoue sur un tenant est journalisée sans
 * interrompre le démarrage : c'est voulu, les autres tenants restent
 * opérationnels. Mais l'échec ne se voyait ensuite nulle part. Le tenant
 * concerné restait bloqué à la migration fautive, et toutes les livraisons
 * suivantes lui passaient à côté sans le moindre signe visible dans le
 * produit. Il a fallu lire les journaux de démarrage pour s'en apercevoir,
 * plusieurs jours après.</p>
 *
 * <p>Le compte des échecs est donc rendu au même endroit que l'identité du
 * build. Les noms des tenants n'y figurent pas — la sonde est publique, et
 * le détail par tenant vit déjà dans la console interne.</p>
 */
@ApplicationScoped
public class MigrationHealth {

    private volatile int applied;
    private final List<String> failedTenants = new CopyOnWriteArrayList<>();

    /** Appelé par le runner de démarrage, au début de la passe. */
    public void reset() {
        applied = 0;
        failedTenants.clear();
    }

    public void recordApplied() {
        applied++;
    }

    public void recordFailure(String tenantSlug) {
        failedTenants.add(tenantSlug);
    }

    public int applied() { return applied; }

    public int failed() { return failedTenants.size(); }

    /** Détail réservé à la console interne. */
    public List<String> failedTenants() { return List.copyOf(failedTenants); }
}
