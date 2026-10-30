package com.ntech.cabosse.importjournal.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.importjournal.entity.ImportFileEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ImportFileRepository {

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ImportFileEntity> coll() {
        return tenantDb.collection(ImportFileEntity.COLLECTION, ImportFileEntity.class);
    }

    public void insert(ImportFileEntity e) {
        coll().insertOne(e);
    }

    public Optional<ImportFileEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<ImportFileEntity> search(String domain, int skip, int limit) {
        List<ImportFileEntity> out = new ArrayList<>();
        coll().find(filter(domain))
                .sort(Sorts.descending("uploadedAt"))
                .skip(skip).limit(limit)
                .into(out);
        return out;
    }

    public long count(String domain) {
        return coll().countDocuments(filter(domain));
    }

    private static Bson filter(String domain) {
        return domain == null || domain.isBlank()
                ? new org.bson.Document()
                : Filters.eq("domain", domain.trim());
    }
}
