package com.ntech.cabosse.treasury.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.treasury.entity.CashCountEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CashCountRepository {

    public static final String COLLECTION = "cash_counts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CashCountEntity> coll() {
        return tenantDb.collection(COLLECTION, CashCountEntity.class);
    }

    public Optional<CashCountEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<CashCountEntity> listByAccount(UUID accountId, int limit) {
        return coll().find(Filters.eq("accountId", accountId))
                .sort(new Document("countedAt", -1))
                .limit(limit)
                .into(new ArrayList<>());
    }

    /** Dernier comptage connu, pour situer le point courant. */
    public Optional<CashCountEntity> lastForAccount(UUID accountId) {
        return Optional.ofNullable(coll().find(Filters.eq("accountId", accountId))
                .sort(new Document("countedAt", -1))
                .first());
    }

    public void insert(CashCountEntity e) { coll().insertOne(e); }

    public void replace(CashCountEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
