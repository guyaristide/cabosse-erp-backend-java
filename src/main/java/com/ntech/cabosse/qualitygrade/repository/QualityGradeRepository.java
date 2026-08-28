package com.ntech.cabosse.qualitygrade.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.qualitygrade.entity.QualityGradeEntity;
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
public class QualityGradeRepository {

    public static final String COLLECTION = "quality_grades";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<QualityGradeEntity> coll() {
        return tenantDb.collection(COLLECTION, QualityGradeEntity.class);
    }

    /** Du meilleur grade au moins bon, puis par code à rang égal. */
    public List<QualityGradeEntity> listAll() {
        return coll().find()
                .sort(new Document("sortOrder", 1).append("code", 1))
                .into(new ArrayList<>());
    }

    public Optional<QualityGradeEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<QualityGradeEntity> findByCode(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.regex("code",
                "^" + java.util.regex.Pattern.quote(code) + "$", "i")).first());
    }

    /** Sans casse : « gr1 » et « GR1 » sont le même grade. */
    public boolean codeExists(String code) {
        return findByCode(code).isPresent();
    }

    public void insert(QualityGradeEntity e) { coll().insertOne(e); }

    public void replace(QualityGradeEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now())));
    }
}
