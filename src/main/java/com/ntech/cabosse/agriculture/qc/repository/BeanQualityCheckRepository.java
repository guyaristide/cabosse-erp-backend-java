package com.ntech.cabosse.agriculture.qc.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.agriculture.qc.entity.BeanQualityCheckEntity;
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
public class BeanQualityCheckRepository {

    public static final String COLLECTION = "bean_quality_checks";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<BeanQualityCheckEntity> coll() {
        return tenantDb.collection(COLLECTION, BeanQualityCheckEntity.class);
    }

    public Optional<BeanQualityCheckEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<BeanQualityCheckEntity> findByDryingBatch(UUID dryingBatchId) {
        return Optional.ofNullable(coll().find(Filters.eq("dryingBatchId", dryingBatchId)).first());
    }

    public long countSearch(Boolean conformFilter, String q) {
        return coll().countDocuments(searchFilter(conformFilter, q));
    }

    public List<BeanQualityCheckEntity> search(Boolean conformFilter, String q,
                                               int skip, int limit) {
        return coll().find(searchFilter(conformFilter, q))
                .sort(new Document("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(Boolean conformFilter, String q) {
        List<Bson> filters = new ArrayList<>();
        if (conformFilter != null) filters.add(Filters.eq("conformOverall", conformFilter));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("lotRef", escaped, "i"),
                    Filters.regex("dryingBatchRef", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(BeanQualityCheckEntity e) { coll().insertOne(e); }

    public void replace(BeanQualityCheckEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
