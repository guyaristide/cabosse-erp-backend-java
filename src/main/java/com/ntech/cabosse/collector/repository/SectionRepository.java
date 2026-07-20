package com.ntech.cabosse.collector.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.collector.entity.SectionEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SectionRepository {

    public static final String COLLECTION = "sections";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SectionEntity> coll() {
        return tenantDb.collection(COLLECTION, SectionEntity.class);
    }

    public List<SectionEntity> listAll() {
        return coll().find().sort(new org.bson.Document("code", 1)).into(new ArrayList<>());
    }

    public Optional<SectionEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(SectionEntity e) { coll().insertOne(e); }
    public void replace(SectionEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active).append("updatedAt", Instant.now())));
    }
}
