package com.ntech.cabosse.shared.tenant;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.ntech.cabosse.shared.persistence.CacheNames;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.UUID;

/**
 * Cache du statut des tenants, alimenté par lecture du plan contrôle.
 * TTL configuré dans {@code application.yml} sous
 * {@code quarkus.cache.caffeine.tenant-registry} (5 minutes par défaut).
 *
 * <p>Une suspension de tenant est donc effective au plus tard 5 minutes
 * après changement de statut. C'est le compromis acceptable entre
 * latence et fraîcheur (cf. multi-tenant-architecture.md §6.3).</p>
 *
 * <p>Si le tenant n'existe pas dans le control plane, on retourne
 * {@link TenantStatus#DELETED} pour bloquer toute opération sans
 * lever d'exception bruyante côté guard.</p>
 */
@ApplicationScoped
public class TenantRegistryCache {

    @Inject
    MongoClient mongoClient;

    @CacheResult(cacheName = CacheNames.TENANT_REGISTRY)
    public TenantStatus statusOf(UUID tenantId) {
        // _id est encodé en BSON binary subtype 4 (uuid-representation: standard) —
        // on filtre avec l'UUID natif, pas son toString().
        Document doc = mongoClient.getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("_id", tenantId))
                .projection(Projections.include("status"))
                .first();

        if (doc == null) return TenantStatus.DELETED;
        String raw = doc.getString("status");
        if (raw == null) return TenantStatus.DELETED;
        try {
            return TenantStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // Statut inconnu en base — on refuse l'accès par défaut.
            return TenantStatus.SUSPENDED;
        }
    }
}
