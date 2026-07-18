package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.FiscalYearEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class FiscalYearRepository {

    public static final String COLLECTION = "fiscal_years";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<FiscalYearEntity> coll() {
        return tenantDb.collection(COLLECTION, FiscalYearEntity.class);
    }

    public Optional<FiscalYearEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<FiscalYearEntity> findByEndDate(LocalDate endDate) {
        return Optional.ofNullable(coll().find(Filters.eq("endDate", endDate)).first());
    }

    /** Dernier exercice arrêté (fin la plus récente), ou vide. */
    public Optional<FiscalYearEntity> findLatest() {
        return Optional.ofNullable(coll().find()
                .sort(new org.bson.Document("endDate", -1))
                .first());
    }

    public List<FiscalYearEntity> listAll() {
        return coll().find()
                .sort(new org.bson.Document("endDate", -1))
                .into(new ArrayList<>());
    }

    public void insert(FiscalYearEntity e) {
        coll().insertOne(e);
    }

    public void replace(FiscalYearEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
