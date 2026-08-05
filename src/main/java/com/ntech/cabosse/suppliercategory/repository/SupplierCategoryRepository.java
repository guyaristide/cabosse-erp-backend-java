package com.ntech.cabosse.suppliercategory.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SupplierCategoryRepository {

    public static final String COLLECTION = "supplier_categories";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SupplierCategoryEntity> coll() {
        return tenantDb.collection(COLLECTION, SupplierCategoryEntity.class);
    }

    public List<SupplierCategoryEntity> listAll() {
        return coll().find().sort(new Document("code", 1)).into(new ArrayList<>());
    }

    public Optional<SupplierCategoryEntity> findById(UUID id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Index par identifiant, pour éviter une lecture par fournisseur. */
    public Map<UUID, SupplierCategoryEntity> byId() {
        Map<UUID, SupplierCategoryEntity> map = new LinkedHashMap<>();
        for (SupplierCategoryEntity e : listAll()) map.put(e.id, e);
        return map;
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(SupplierCategoryEntity e) { coll().insertOne(e); }
    public void replace(SupplierCategoryEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active).append("updatedAt", Instant.now())));
    }
}
