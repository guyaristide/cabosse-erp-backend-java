package com.ntech.cabosse.department.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.department.entity.DepartmentEntity;
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
public class DepartmentRepository {

    public static final String COLLECTION = "departments";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DepartmentEntity> coll() {
        return tenantDb.collection(COLLECTION, DepartmentEntity.class);
    }

    public List<DepartmentEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<DepartmentEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(DepartmentEntity e) { coll().insertOne(e); }

    public void replace(DepartmentEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
