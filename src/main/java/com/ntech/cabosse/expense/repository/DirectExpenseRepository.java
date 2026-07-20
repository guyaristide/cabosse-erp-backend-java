package com.ntech.cabosse.expense.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.expense.entity.DirectExpenseEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dépenses directes ACH-03 (immuables). Tenant-scopé. */
@ApplicationScoped
public class DirectExpenseRepository {

    public static final String COLLECTION = "direct_expenses";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DirectExpenseEntity> coll() {
        return tenantDb.collection(COLLECTION, DirectExpenseEntity.class);
    }

    public Optional<DirectExpenseEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String kind) {
        return (kind == null || kind.isBlank())
                ? new org.bson.Document() : Filters.eq("kind", kind);
    }

    public long countSearch(String kind) {
        return coll().countDocuments(searchFilter(kind));
    }

    public List<DirectExpenseEntity> search(String kind, int skip, int limit) {
        return coll().find(searchFilter(kind))
                .sort(new org.bson.Document("createdAt", -1))
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    public void insert(DirectExpenseEntity e) { coll().insertOne(e); }
}
