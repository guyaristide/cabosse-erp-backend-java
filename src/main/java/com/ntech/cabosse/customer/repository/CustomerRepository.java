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

    public long countSearch(String q, String type) {
        return coll().countDocuments(searchFilter(q, type));
    }

    public List<CustomerEntity> search(String q, String type, int skip, int limit) {
        return coll().find(searchFilter(q, type))
                .sort(new org.bson.Document("name", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static org.bson.conversions.Bson searchFilter(String q, String type) {
        List<org.bson.conversions.Bson> filters = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("name", escaped, "i"),
                    Filters.regex("code", escaped, "i")));
        }
        if (type != null && !type.isBlank()) {
            filters.add(Filters.eq("type", type.trim()));
        }
        return filters.isEmpty() ? new org.bson.Document() : Filters.and(filters);
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
