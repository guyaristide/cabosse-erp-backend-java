package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.accounting.entity.QuarantineStatus;
import com.ntech.cabosse.accounting.entity.QuarantinedPostingEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Écritures retenues faute de période ouverte, dans la base du tenant. */
@ApplicationScoped
public class QuarantinedPostingRepository {

    public static final String COLLECTION = "accounting_quarantine";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<QuarantinedPostingEntity> coll() {
        return tenantDb.collection(COLLECTION, QuarantinedPostingEntity.class);
    }

    public void insert(QuarantinedPostingEntity e) {
        coll().insertOne(e);
    }

    public Optional<QuarantinedPostingEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public void replace(QuarantinedPostingEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }

    /**
     * Y a-t-il déjà une ligne en attente pour cette origine ? Le rejeu d'une
     * même opération ne doit pas empiler les demandes de régularisation.
     */
    public Optional<QuarantinedPostingEntity> findPendingBySource(String sourceType, UUID sourceId) {
        return Optional.ofNullable(coll().find(Filters.and(
                Filters.eq("sourceType", sourceType),
                Filters.eq("sourceId", sourceId),
                Filters.eq("status", QuarantineStatus.PENDING.name())
        )).first());
    }

    public List<QuarantinedPostingEntity> list(QuarantineStatus status, int limit, int skip) {
        Bson filter = status == null
                ? new org.bson.Document()
                : Filters.eq("status", status.name());
        return coll().find(filter)
                .sort(Sorts.ascending("date"))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, Math.min(limit, 200)))
                .into(new ArrayList<>());
    }

    public long count(QuarantineStatus status) {
        return status == null
                ? coll().countDocuments()
                : coll().countDocuments(Filters.eq("status", status.name()));
    }

    /** Nombre de lignes en attente dont la date tombe dans la période donnée. */
    public long countPendingInPeriod(java.time.LocalDate from, java.time.LocalDate to) {
        return coll().countDocuments(Filters.and(
                Filters.eq("status", QuarantineStatus.PENDING.name()),
                Filters.gte("date", from),
                Filters.lte("date", to)));
    }
}
