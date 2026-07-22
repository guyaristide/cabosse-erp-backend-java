package com.ntech.cabosse.region.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.region.entity.RegionEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RegionRepository {

    public static final String COLLECTION = "regions";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<RegionEntity> coll() {
        return tenantDb.collection(COLLECTION, RegionEntity.class);
    }

    public List<RegionEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<RegionEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(RegionEntity e) { coll().insertOne(e); }

    public void replace(RegionEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
