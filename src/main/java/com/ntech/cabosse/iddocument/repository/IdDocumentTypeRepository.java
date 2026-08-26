package com.ntech.cabosse.iddocument.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.iddocument.entity.IdDocumentTypeEntity;
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
public class IdDocumentTypeRepository {

    public static final String COLLECTION = "id_document_types";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<IdDocumentTypeEntity> coll() {
        return tenantDb.collection(COLLECTION, IdDocumentTypeEntity.class);
    }

    public List<IdDocumentTypeEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<IdDocumentTypeEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Sans casse : « CAC001 » et « cac001 » sont le même code. */
    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.regex("code",
                "^" + java.util.regex.Pattern.quote(code) + "$", "i")) > 0;
    }

    public void insert(IdDocumentTypeEntity e) { coll().insertOne(e); }

    public void replace(IdDocumentTypeEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
