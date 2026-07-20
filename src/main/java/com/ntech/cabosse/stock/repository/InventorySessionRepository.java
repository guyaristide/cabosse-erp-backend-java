package com.ntech.cabosse.stock.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.stock.entity.InventorySessionEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class InventorySessionRepository {

    public static final String COLLECTION = "inventory_sessions";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<InventorySessionEntity> coll() {
        return tenantDb.collection(COLLECTION, InventorySessionEntity.class);
    }

    public Optional<InventorySessionEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Une seule session non close par site à la fois. */
    public boolean hasOpenSession(UUID siteId) {
        return coll().countDocuments(Filters.and(
                Filters.eq("siteId", siteId),
                Filters.in("status",
                        InventorySessionEntity.STATUS_OPEN,
                        InventorySessionEntity.STATUS_SUBMITTED)
        )) > 0;
    }

    public long countSearch(UUID siteId, String status) {
        return coll().countDocuments(searchFilter(siteId, status));
    }

    public List<InventorySessionEntity> search(UUID siteId, String status, int skip, int limit) {
        return coll().find(searchFilter(siteId, status))
                .sort(new Document("openedAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(UUID siteId, String status) {
        List<Bson> filters = new ArrayList<>();
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (status != null && !status.isBlank()) filters.add(Filters.eq("status", status));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(InventorySessionEntity e) { coll().insertOne(e); }

    /**
     * Verrou de validation atomique : SUBMITTED vers VALIDATED. Le perdant
     * d'une double validation voit {@code false} — les ajustements de stock
     * ne sont jamais appliqués deux fois (règle concurrence).
     */
    public boolean tryMarkValidated(java.util.UUID id) {
        return coll().updateOne(
                com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("_id", id),
                        com.mongodb.client.model.Filters.eq("status", InventorySessionEntity.STATUS_SUBMITTED)
                ),
                com.mongodb.client.model.Updates.set("status", InventorySessionEntity.STATUS_VALIDATED)
        ).getModifiedCount() == 1;
    }

    public void replace(InventorySessionEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
