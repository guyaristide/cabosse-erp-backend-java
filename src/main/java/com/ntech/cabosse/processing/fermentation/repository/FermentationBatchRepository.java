package com.ntech.cabosse.processing.fermentation.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchEntity;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchStatus;
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
public class FermentationBatchRepository {

    public static final String COLLECTION = "fermentation_batches";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<FermentationBatchEntity> coll() {
        return tenantDb.collection(COLLECTION, FermentationBatchEntity.class);
    }

    public Optional<FermentationBatchEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<FermentationBatchEntity> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return coll().find(Filters.in("_id", ids)).into(new ArrayList<>());
    }

    public List<FermentationBatchEntity> findContainingHarvest(UUID harvestId) {
        return coll().find(Filters.eq("harvestIds", harvestId)).into(new ArrayList<>());
    }

    public List<FermentationBatchEntity> search(FermentationBatchStatus statusFilter, String q) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.regex("ref", escaped, "i"));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("startedAt", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    public void insert(FermentationBatchEntity e) { coll().insertOne(e); }

    public void replace(FermentationBatchEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
