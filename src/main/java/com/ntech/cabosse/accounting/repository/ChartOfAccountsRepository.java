package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès au plan comptable SYSCOHADA du tenant. */
@ApplicationScoped
public class ChartOfAccountsRepository {

    public static final String COLLECTION = "chart_of_accounts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ChartOfAccountsEntity> coll() {
        return tenantDb.collection(COLLECTION, ChartOfAccountsEntity.class);
    }

    public Optional<ChartOfAccountsEntity> findByNumber(String number) {
        return Optional.ofNullable(coll().find(Filters.eq("number", number)).first());
    }

    public Optional<ChartOfAccountsEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Liste filtrée — option famille pour le panneau "plan comptable filtré". */
    public List<ChartOfAccountsEntity> list(AccountFamily family) {
        var filter = family == null
                ? new Document()
                : Filters.eq("family", family.name());
        return coll().find(filter)
                .sort(new Document("number", 1))
                .into(new ArrayList<>());
    }

    public boolean numberExists(String number) {
        return coll().countDocuments(Filters.eq("number", number)) > 0;
    }

    public void insert(ChartOfAccountsEntity e) {
        coll().insertOne(e);
    }

    public void replace(ChartOfAccountsEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
