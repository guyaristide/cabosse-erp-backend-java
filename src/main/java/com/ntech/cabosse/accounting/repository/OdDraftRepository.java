package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.OdDraftEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class OdDraftRepository {

    public static final String COLLECTION = "od_drafts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<OdDraftEntity> coll() {
        return tenantDb.collection(COLLECTION, OdDraftEntity.class);
    }

    public Optional<OdDraftEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public long countSearch(String status) {
        return coll().countDocuments(searchFilter(status));
    }

    public List<OdDraftEntity> search(String status, int skip, int limit) {
        return coll().find(searchFilter(status))
                .sort(new Document("date", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    /** Brouillons non validés dont la date tombe dans [from, to] — contrôle de clôture. */
    public long countDraftsInPeriod(LocalDate from, LocalDate to) {
        return coll().countDocuments(Filters.and(
                Filters.eq("status", OdDraftEntity.STATUS_DRAFT),
                Filters.gte("date", from),
                Filters.lte("date", to)
        ));
    }

    private static Bson searchFilter(String status) {
        if (status == null || status.isBlank()) return new Document();
        return Filters.eq("status", status);
    }

    public void insert(OdDraftEntity e) { coll().insertOne(e); }

    public void replace(OdDraftEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void delete(UUID id) { coll().deleteOne(Filters.eq("_id", id)); }
}
