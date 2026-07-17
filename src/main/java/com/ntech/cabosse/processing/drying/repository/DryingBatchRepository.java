package com.ntech.cabosse.processing.drying.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.processing.drying.entity.DryingBatchEntity;
import com.ntech.cabosse.processing.drying.entity.DryingBatchStatus;
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
public class DryingBatchRepository {

    public static final String COLLECTION = "drying_batches";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DryingBatchEntity> coll() {
        return tenantDb.collection(COLLECTION, DryingBatchEntity.class);
    }

    public Optional<DryingBatchEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<DryingBatchEntity> findContainingFermentation(UUID fermentationBatchId) {
        return coll().find(Filters.eq("fermentationBatchIds", fermentationBatchId))
                .into(new ArrayList<>());
    }

    public long countSearch(DryingBatchStatus statusFilter, String q) {
        return coll().countDocuments(searchFilter(statusFilter, q));
    }

    public List<DryingBatchEntity> search(DryingBatchStatus statusFilter, String q, int skip, int limit) {
        return coll().find(searchFilter(statusFilter, q))
                .sort(new Document("startedAt", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(DryingBatchStatus statusFilter, String q) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.regex("ref", escaped, "i"));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(DryingBatchEntity e) { coll().insertOne(e); }

    public void replace(DryingBatchEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
