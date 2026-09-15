package com.ntech.cabosse.importjournal.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Le journal des imports de la structure courante. */
@ApplicationScoped
public class ImportRunRepository {

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ImportRunEntity> coll() {
        return tenantDb.collection(ImportRunEntity.COLLECTION, ImportRunEntity.class);
    }

    public void insert(ImportRunEntity e) {
        coll().insertOne(e);
    }

    public Optional<ImportRunEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Du plus récent au plus ancien : on cherche presque toujours le dernier. */
    public List<ImportRunEntity> search(String domain, int skip, int limit) {
        var filter = domain == null || domain.isBlank()
                ? new org.bson.Document() : Filters.eq("domain", domain);
        return coll().find(filter)
                .sort(Sorts.descending("at"))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, limit))
                .into(new ArrayList<>());
    }

    public long count(String domain) {
        var filter = domain == null || domain.isBlank()
                ? new org.bson.Document() : Filters.eq("domain", domain);
        return coll().countDocuments(filter);
    }
}
