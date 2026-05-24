package com.ntech.cabosse.shared.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provider d'accès au plan donnée du tenant courant. Résout la base
 * cible à chaque appel à partir du {@link TenantContext} hydraté par
 * le {@code TenantContextFilter} (en requête HTTP) ou par
 * {@code TenantAwareExecutor} (hors HTTP).
 *
 * <p>Si {@link TenantContext} n'est pas initialisé, l'appel échoue avec
 * une {@code IllegalStateException} explicite — pas de fuite silencieuse
 * possible (cf. multi-tenant-architecture.md §7.2).</p>
 */
@ApplicationScoped
public class TenantMongoDatabaseProvider {

    @Inject
    MongoClient mongoClient;

    @Inject
    TenantContext tenantContext;

    public MongoDatabase database() {
        return mongoClient.getDatabase(tenantContext.databaseName());
    }

    public <T> MongoCollection<T> collection(String name, Class<T> type) {
        return database().getCollection(name, type);
    }
}
