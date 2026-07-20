package com.ntech.cabosse.analytics.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.analytics.entity.CostCenterEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CostCenterRepository {

    public static final String COLLECTION = "cost_centers";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CostCenterEntity> coll() {
        return tenantDb.collection(COLLECTION, CostCenterEntity.class);
    }

    public List<CostCenterEntity> listAll() {
        return coll().find().sort(new org.bson.Document("code", 1)).into(new ArrayList<>());
    }

    public Optional<CostCenterEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    /** Codes des centres actifs — sert à la validation d'imputation. */
    public Set<String> activeCodes() {
        return coll().find(Filters.eq("active", true)).into(new ArrayList<>())
                .stream().map(c -> c.code).collect(Collectors.toSet());
    }

    /** Indexe les centres par code — sert la dérivation du programme (règle v8). */
    public java.util.Map<String, CostCenterEntity> byCode() {
        return coll().find().into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(c -> c.code, c -> c, (a, b) -> a));
    }

    public void insert(CostCenterEntity e) { coll().insertOne(e); }
    public void replace(CostCenterEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
