package com.ntech.cabosse.crop.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.crop.entity.CropEntity;
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
public class CropRepository {

    public static final String COLLECTION = "crops";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CropEntity> coll() {
        return tenantDb.collection(COLLECTION, CropEntity.class);
    }

    public List<CropEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<CropEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<CropEntity> findByCode(String code) {
        return Optional.ofNullable(coll().find(Filters.eq("code", code)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(CropEntity e) { coll().insertOne(e); }

    public void replace(CropEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
