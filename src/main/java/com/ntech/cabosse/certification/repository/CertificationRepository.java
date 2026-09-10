package com.ntech.cabosse.certification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.certification.entity.CertificationEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class CertificationRepository {

    public static final String COLLECTION = "certifications";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CertificationEntity> coll() {
        return tenantDb.collection(COLLECTION, CertificationEntity.class);
    }

    public List<CertificationEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<CertificationEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Sans casse : « RA » et « ra » sont le même code. */
    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.regex("code",
                "^" + Pattern.quote(code) + "$", "i")) > 0;
    }

    public void insert(CertificationEntity e) { coll().insertOne(e); }

    public void replace(CertificationEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
