package com.ntech.cabosse.treasury.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.treasury.entity.TreasuryTransferEntity;
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
public class TreasuryTransferRepository {

    public static final String COLLECTION = "treasury_transfers";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<TreasuryTransferEntity> coll() {
        return tenantDb.collection(COLLECTION, TreasuryTransferEntity.class);
    }

    public Optional<TreasuryTransferEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson filter(LocalDate from, LocalDate to, String status, UUID accountId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("sentAt", from));
        if (to != null) filters.add(Filters.lte("sentAt", to));
        if (status != null && !status.isBlank()) filters.add(Filters.eq("status", status));
        if (accountId != null) {
            filters.add(Filters.or(
                    Filters.eq("fromAccountId", accountId),
                    Filters.eq("toAccountId", accountId)));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(LocalDate from, LocalDate to, String status, UUID accountId) {
        return coll().countDocuments(filter(from, to, status, accountId));
    }

    public List<TreasuryTransferEntity> search(LocalDate from, LocalDate to, String status,
                                               UUID accountId, int skip, int limit) {
        return coll().find(filter(from, to, status, accountId))
                .sort(new Document("sentAt", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /** Tous les transferts d'une période, pour le rapprochement. */
    public List<TreasuryTransferEntity> listForPeriod(LocalDate from, LocalDate to) {
        return coll().find(filter(from, to, null, null))
                .sort(new Document("sentAt", 1))
                .into(new ArrayList<>());
    }

    public void insert(TreasuryTransferEntity e) { coll().insertOne(e); }

    public void replace(TreasuryTransferEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException(Messages.msg("m.trs-transfer-concurrent-update"));
        }
    }
}
