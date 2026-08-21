package com.ntech.cabosse.permission.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.permission.entity.TenantRoleEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TenantRoleRepository {

    public static final String COLLECTION = "tenant_roles";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<TenantRoleEntity> coll() {
        return tenantDb.collection(COLLECTION, TenantRoleEntity.class);
    }

    public List<TenantRoleEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public List<TenantRoleEntity> listByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return coll().find(Filters.in("_id", ids)).into(new ArrayList<>());
    }

    public Optional<TenantRoleEntity> findById(UUID id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(TenantRoleEntity e) { coll().insertOne(e); }
    public void replace(TenantRoleEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(Filters.eq("_id", id), new Document("$set", new Document()
                .append("active", active).append("updatedAt", Instant.now())));
    }

    public void delete(UUID id) { coll().deleteOne(Filters.eq("_id", id)); }
}
