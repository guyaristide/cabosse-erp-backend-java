package com.ntech.cabosse.production.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ManufacturingOrderRepository {

    public static final String COLLECTION = "manufacturing_orders";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ManufacturingOrderEntity> coll() {
        return tenantDb.collection(COLLECTION, ManufacturingOrderEntity.class);
    }

    public Optional<ManufacturingOrderEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<ManufacturingOrderEntity> listAll() {
        return coll().find()
                .sort(new Document("scheduledDate", -1))
                .into(new ArrayList<>());
    }

    /** Recherche filtrée pour la liste UI (par statut, par recherche libre, par site). */
    public List<ManufacturingOrderEntity> search(OfStatus status, String q, UUID siteId) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (siteId != null) {
            filters.add(Filters.eq("siteId", siteId));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("recipeName", escaped, "i"),
                    Filters.regex("finishedProductName", escaped, "i"),
                    Filters.regex("lotRef", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("scheduledDate", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<ManufacturingOrderEntity> listByRecipe(UUID recipeId) {
        return coll().find(Filters.eq("recipeId", recipeId))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<ManufacturingOrderEntity> listByFinishedProduct(UUID articleId) {
        return coll().find(Filters.eq("finishedProductId", articleId))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<ManufacturingOrderEntity> findByLot(String lotRef) {
        return coll().find(Filters.eq("lotRef", lotRef))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(ManufacturingOrderEntity e) {
        coll().insertOne(e);
    }

    public void replace(ManufacturingOrderEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
