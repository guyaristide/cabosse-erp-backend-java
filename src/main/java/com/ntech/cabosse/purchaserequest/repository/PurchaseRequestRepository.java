package com.ntech.cabosse.purchaserequest.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.purchaserequest.entity.PurchaseRequestEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PurchaseRequestRepository {

    public static final String COLLECTION = "purchase_requests";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<PurchaseRequestEntity> coll() {
        return tenantDb.collection(COLLECTION, PurchaseRequestEntity.class);
    }

    public Optional<PurchaseRequestEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String status) {
        return (status == null || status.isBlank())
                ? new org.bson.Document()
                : Filters.eq("status", status);
    }

    public long countSearch(String status) {
        return coll().countDocuments(searchFilter(status));
    }

    public List<PurchaseRequestEntity> search(String status, int skip, int limit) {
        return coll().find(searchFilter(status))
                .sort(new org.bson.Document("createdAt", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    public void insert(PurchaseRequestEntity e) { coll().insertOne(e); }

    /** Remplacement avec lock optimiste (patron BC). */
    public void replace(PurchaseRequestEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException(Messages.msg("m.prq-concurrent-update"));
        }
    }

    public void delete(UUID id) { coll().deleteOne(Filters.eq("_id", id)); }
}
