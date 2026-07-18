package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.AccountingPeriodEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AccountingPeriodRepository {

    public static final String COLLECTION = "accounting_periods";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<AccountingPeriodEntity> coll() {
        return tenantDb.collection(COLLECTION, AccountingPeriodEntity.class);
    }

    public Optional<AccountingPeriodEntity> findByPeriod(String period) {
        return Optional.ofNullable(coll().find(Filters.eq("period", period)).first());
    }

    public boolean isLocked(String period) {
        return coll().countDocuments(Filters.and(
                Filters.eq("period", period),
                Filters.eq("status", AccountingPeriodEntity.STATUS_LOCKED)
        )) > 0;
    }

    public List<AccountingPeriodEntity> listAll() {
        return coll().find()
                .sort(new org.bson.Document("period", -1))
                .into(new ArrayList<>());
    }

    public void insert(AccountingPeriodEntity e) {
        coll().insertOne(e);
    }

    public void replace(AccountingPeriodEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
