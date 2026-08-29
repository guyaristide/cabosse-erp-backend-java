package com.ntech.cabosse.qualitygrade.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.qualitygrade.entity.QualityNormEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class QualityNormRepository {

    public static final String COLLECTION = "quality_norms";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<QualityNormEntity> coll() {
        return tenantDb.collection(COLLECTION, QualityNormEntity.class);
    }

    public List<QualityNormEntity> listAll() {
        return coll().find()
                .sort(new Document("sortOrder", 1).append("elementCode", 1))
                .into(new ArrayList<>());
    }

    public Optional<QualityNormEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<QualityNormEntity> findByElement(String elementCode) {
        if (elementCode == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.regex("elementCode",
                "^" + java.util.regex.Pattern.quote(elementCode) + "$", "i")).first());
    }

    public void insert(QualityNormEntity e) { coll().insertOne(e); }

    public void replace(QualityNormEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active).append("updatedAt", Instant.now())));
    }
}
