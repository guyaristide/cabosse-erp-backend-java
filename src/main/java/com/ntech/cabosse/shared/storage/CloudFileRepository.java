package com.ntech.cabosse.shared.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository {@link CloudFileEntity} routé par {@link CloudFileScope}.
 *
 * <ul>
 *   <li>{@link CloudFileScope#PLATFORM} → {@code cabosse_control.cloud_files}.</li>
 *   <li>{@link CloudFileScope#TENANT}   → {@code <tenant_db>.cloud_files}, via
 *       {@link TenantMongoDatabaseProvider} (le tenant est lu sur le
 *       {@code TenantContext}).</li>
 * </ul>
 *
 * <p>Aucune logique métier ici. Les services consomment via
 * {@link FileUploadService}, pas via ce repo directement
 * (cf. règle CLAUDE.md §6.4).</p>
 */
@ApplicationScoped
public class CloudFileRepository {

    public static final String COLLECTION = "cloud_files";

    @Inject MongoClient mongoClient;
    @Inject TenantMongoDatabaseProvider tenantDb;
    @Inject TenantContext tenantContext;

    private MongoCollection<CloudFileEntity> coll(CloudFileScope scope) {
        return switch (scope) {
            case PLATFORM -> mongoClient
                    .getDatabase(ControlPlane.DATABASE)
                    .getCollection(COLLECTION, CloudFileEntity.class);
            case TENANT -> tenantDb.collection(COLLECTION, CloudFileEntity.class);
        };
    }

    public CloudFileEntity findById(CloudFileScope scope, UUID fileId) {
        return coll(scope).find(Filters.eq("_id", fileId)).first();
    }

    public void insert(CloudFileScope scope, CloudFileEntity file) {
        coll(scope).insertOne(file);
    }

    public void replace(CloudFileScope scope, CloudFileEntity file) {
        coll(scope).replaceOne(Filters.eq("_id", file.id), file);
    }

    public void archiveById(CloudFileScope scope, UUID fileId) {
        coll(scope).updateOne(
                Filters.eq("_id", fileId),
                new org.bson.Document("$set",
                        new org.bson.Document("archivedAt", Instant.now()))
        );
    }

    public void deleteById(CloudFileScope scope, UUID fileId) {
        coll(scope).deleteOne(Filters.eq("_id", fileId));
    }

    public void touchLastAccessed(CloudFileScope scope, UUID fileId) {
        coll(scope).updateOne(
                Filters.eq("_id", fileId),
                new org.bson.Document("$set",
                        new org.bson.Document("lastAccessedAt", Instant.now()))
        );
    }

    /**
     * Retourne le tenantId du contexte courant, pour dénormalisation dans
     * les écritures TENANT. Renvoie {@code null} si pas de contexte (à
     * n'utiliser qu'en scope PLATFORM).
     */
    public UUID currentTenantId() {
        return tenantContext.isInitialized() ? tenantContext.tenantId() : null;
    }
}
