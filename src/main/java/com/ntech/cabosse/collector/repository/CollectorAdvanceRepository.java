package com.ntech.cabosse.collector.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CollectorAdvanceRepository {

    public static final String COLLECTION = "collector_advances";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CollectorAdvanceEntity> coll() {
        return tenantDb.collection(COLLECTION, CollectorAdvanceEntity.class);
    }

    public Optional<CollectorAdvanceEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String status) {
        return (status == null || status.isBlank())
                ? new org.bson.Document() : Filters.eq("status", status);
    }

    public long countSearch(String status) {
        return coll().countDocuments(searchFilter(status));
    }

    public List<CollectorAdvanceEntity> search(String status, int skip, int limit) {
        return coll().find(searchFilter(status))
                .sort(new org.bson.Document("createdAt", -1))
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    public void insert(CollectorAdvanceEntity e) { coll().insertOne(e); }

    public void replace(CollectorAdvanceEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException("L'avance a été modifiée entre-temps. Rechargez la page.");
        }
    }
}
