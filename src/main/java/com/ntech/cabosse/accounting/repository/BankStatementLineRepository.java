package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.BankStatementLineEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;
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

/** Accès aux lignes d'extrait — usage massif pour le rapprochement. */
@ApplicationScoped
public class BankStatementLineRepository {

    public static final String COLLECTION = "bank_statement_lines";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<BankStatementLineEntity> coll() {
        return tenantDb.collection(COLLECTION, BankStatementLineEntity.class);
    }

    public Optional<BankStatementLineEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<BankStatementLineEntity> listByStatement(UUID statementId, BankStatementLineStatus statusFilter) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("statementId", statementId));
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        return coll().find(Filters.and(filters))
                .sort(new Document("operationDate", 1).append("createdAt", 1))
                .into(new ArrayList<>());
    }

    public boolean hashExists(UUID bankAccountId, String sourceHash) {
        return coll().countDocuments(Filters.and(
                Filters.eq("bankAccountId", bankAccountId),
                Filters.eq("sourceHash", sourceHash)
        )) > 0;
    }

    public long countMatched(UUID statementId) {
        return coll().countDocuments(Filters.and(
                Filters.eq("statementId", statementId),
                Filters.in("status", BankStatementLineStatus.MATCHED.name(),
                        BankStatementLineStatus.IGNORED.name())
        ));
    }

    public List<BankStatementLineEntity> findByAmountAround(UUID bankAccountId,
                                                            java.math.BigDecimal amount,
                                                            LocalDate date,
                                                            int daysWindow) {
        LocalDate from = date.minusDays(daysWindow);
        LocalDate to = date.plusDays(daysWindow);
        return coll().find(Filters.and(
                Filters.eq("bankAccountId", bankAccountId),
                Filters.eq("amount", new org.bson.types.Decimal128(amount)),
                Filters.gte("operationDate", from),
                Filters.lte("operationDate", to)
        )).into(new ArrayList<>());
    }

    public void insert(BankStatementLineEntity e) { coll().insertOne(e); }
    public void replace(BankStatementLineEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
