package com.ntech.cabosse.locality.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.locality.entity.LocalityEntity;
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
public class LocalityRepository {

    public static final String COLLECTION = "localities";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<LocalityEntity> coll() {
        return tenantDb.collection(COLLECTION, LocalityEntity.class);
    }

    public List<LocalityEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<LocalityEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Sans casse : « CAC001 » et « cac001 » sont le même code. */
    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.regex("code",
                "^" + java.util.regex.Pattern.quote(code) + "$", "i")) > 0;
    }

    public void insert(LocalityEntity e) { coll().insertOne(e); }

    public void replace(LocalityEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
