package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.TvaDeclarationEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Accès aux déclarations TVA mensuelles persistées. */
@ApplicationScoped
public class TvaDeclarationRepository {

    public static final String COLLECTION = "tva_declarations";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<TvaDeclarationEntity> coll() {
        return tenantDb.collection(COLLECTION, TvaDeclarationEntity.class);
    }

    public Optional<TvaDeclarationEntity> findByYearMonth(String yearMonth) {
        return Optional.ofNullable(coll().find(Filters.eq("yearMonth", yearMonth)).first());
    }

    public List<TvaDeclarationEntity> listRecent(int limit) {
        return coll().find()
                .sort(new Document("yearMonth", -1))
                .limit(limit)
                .into(new ArrayList<>());
    }

    public void insert(TvaDeclarationEntity e) {
        coll().insertOne(e);
    }

    public void replace(TvaDeclarationEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
