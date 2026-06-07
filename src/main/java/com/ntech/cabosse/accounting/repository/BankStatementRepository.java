package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.BankStatementEntity;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux extraits bancaires (l'en-tête, sans les lignes). */
@ApplicationScoped
public class BankStatementRepository {

    public static final String COLLECTION = "bank_statements";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<BankStatementEntity> coll() {
        return tenantDb.collection(COLLECTION, BankStatementEntity.class);
    }

    public Optional<BankStatementEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<BankStatementEntity> list(UUID bankAccountId, BankStatementStatus statusFilter) {
        List<Bson> filters = new ArrayList<>();
        if (bankAccountId != null) filters.add(Filters.eq("bankAccountId", bankAccountId));
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("importedAt", -1))
                .into(new ArrayList<>());
    }

    public void insert(BankStatementEntity e) { coll().insertOne(e); }
    public void replace(BankStatementEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
