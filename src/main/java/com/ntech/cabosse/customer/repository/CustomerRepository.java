package com.ntech.cabosse.customer.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CustomerRepository {

    public static final String COLLECTION = "customers";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CustomerEntity> coll() {
        return tenantDb.collection(COLLECTION, CustomerEntity.class);
    }

    public List<CustomerEntity> listAll() {
        return coll().find().sort(new org.bson.Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<CustomerEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    /**
     * Recherche par nom <em>case-insensitive</em>, trim implicite côté
     * appelant. Utile à l'import vente pour faire un resolve-or-create.
     */
    public Optional<CustomerEntity> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String regex = "^" + java.util.regex.Pattern.quote(name.trim()) + "$";
        return Optional.ofNullable(
                coll().find(Filters.regex("name", regex, "i")).first());
    }

    public void insert(CustomerEntity e) { coll().insertOne(e); }
    public void replace(CustomerEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
