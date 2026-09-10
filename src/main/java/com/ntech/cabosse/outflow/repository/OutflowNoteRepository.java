package com.ntech.cabosse.outflow.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.outflow.entity.OutflowNoteEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OutflowNoteRepository {

    public static final String COLLECTION = "outflow_notes";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<OutflowNoteEntity> coll() {
        return tenantDb.collection(COLLECTION, OutflowNoteEntity.class);
    }

    public Optional<OutflowNoteEntity> findByRef(String ref) {
        return Optional.ofNullable(coll().find(Filters.eq("ref", ref)).first());
    }

    public List<OutflowNoteEntity> list() {
        return coll().find()
                .sort(new Document("date", -1).append("ref", -1))
                .into(new ArrayList<>());
    }

    public void insert(OutflowNoteEntity e) { coll().insertOne(e); }
}
