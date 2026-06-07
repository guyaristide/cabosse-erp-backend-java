package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux comptes bancaires / caisses du tenant. */
@ApplicationScoped
public class BankAccountRepository {

    public static final String COLLECTION = "bank_accounts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<BankAccountEntity> coll() {
        return tenantDb.collection(COLLECTION, BankAccountEntity.class);
    }

    public Optional<BankAccountEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<BankAccountEntity> listActive() {
        return coll().find(Filters.eq("active", true))
                .sort(new Document("label", 1))
                .into(new ArrayList<>());
    }

    public List<BankAccountEntity> listAll() {
        return coll().find()
                .sort(new Document("label", 1))
                .into(new ArrayList<>());
    }

    public void insert(BankAccountEntity e) {
        coll().insertOne(e);
    }

    public void replace(BankAccountEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }

    public void deleteById(UUID id) {
        coll().deleteOne(Filters.eq("_id", id));
    }
}
