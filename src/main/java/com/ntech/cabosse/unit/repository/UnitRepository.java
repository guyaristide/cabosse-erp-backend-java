package com.ntech.cabosse.unit.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.unit.entity.UnitEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UnitRepository {

    public static final String COLLECTION = "units";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<UnitEntity> coll() {
        return tenantDb.collection(COLLECTION, UnitEntity.class);
    }

    public List<UnitEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<UnitEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Sans casse : « CAC001 » et « cac001 » sont le même code. */
    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.regex("code",
                "^" + java.util.regex.Pattern.quote(code) + "$", "i")) > 0;
    }

    public void insert(UnitEntity e) { coll().insertOne(e); }

    public void replace(UnitEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
