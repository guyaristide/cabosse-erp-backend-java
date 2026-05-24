package com.ntech.cabosse.expensetype.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.expensetype.entity.ExpenseTypeEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ExpenseTypeRepository {

    public static final String COLLECTION = "expense_types";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ExpenseTypeEntity> coll() {
        return tenantDb.collection(COLLECTION, ExpenseTypeEntity.class);
    }

    public List<ExpenseTypeEntity> listAll() {
        return coll().find().sort(new org.bson.Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<ExpenseTypeEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(ExpenseTypeEntity e) { coll().insertOne(e); }
    public void replace(ExpenseTypeEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
