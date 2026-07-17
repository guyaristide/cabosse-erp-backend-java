package com.ntech.cabosse.supplier.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SupplierRepository {

    public static final String COLLECTION = "suppliers";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SupplierEntity> coll() {
        return tenantDb.collection(COLLECTION, SupplierEntity.class);
    }

    public List<SupplierEntity> listAll() {
        return coll().find().sort(new org.bson.Document("name", 1)).into(new ArrayList<>());
    }

    public long countSearch(String q) {
        return coll().countDocuments(searchFilter(q));
    }

    public List<SupplierEntity> search(String q, int skip, int limit) {
        return coll().find(searchFilter(q))
                .sort(new org.bson.Document("name", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static org.bson.conversions.Bson searchFilter(String q) {
        if (q == null || q.isBlank()) return new org.bson.Document();
        String escaped = java.util.regex.Pattern.quote(q.trim());
        return Filters.or(
                Filters.regex("name", escaped, "i"),
                Filters.regex("code", escaped, "i")
        );
    }

    public Optional<SupplierEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    /**
     * Recherche par nom <em>case-insensitive</em>, trim implicite côté
     * appelant. Utile à l'import pour faire un resolve-or-create.
     */
    public Optional<SupplierEntity> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        // Regex ancrée + insensible à la casse. Le nom est traité comme literal
        // via Pattern.quote pour ignorer les méta-caractères regex.
        String regex = "^" + java.util.regex.Pattern.quote(name.trim()) + "$";
        return Optional.ofNullable(
                coll().find(Filters.regex("name", regex, "i")).first());
    }

    public void insert(SupplierEntity e) { coll().insertOne(e); }
    public void replace(SupplierEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
