package com.ntech.cabosse.analytics.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.analytics.entity.AllocationKeyEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AllocationKeyRepository {

    public static final String COLLECTION = "allocation_keys";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<AllocationKeyEntity> coll() {
        return tenantDb.collection(COLLECTION, AllocationKeyEntity.class);
    }

    public List<AllocationKeyEntity> listAll() {
        return coll().find().sort(new org.bson.Document("code", 1)).into(new ArrayList<>());
    }

    public Optional<AllocationKeyEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<AllocationKeyEntity> findByCode(String code) {
        return Optional.ofNullable(coll().find(Filters.eq("code", code)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(AllocationKeyEntity e) { coll().insertOne(e); }
    public void replace(AllocationKeyEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now())));
    }
}
