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

    /**
     * Recherche libre pour la palette. Le chargement se cite tantôt par le
     * numéro de bordereau de sortie, tantôt par le numéro de chargement,
     * tantôt par la plaque du camion : les trois doivent mener au même
     * document.
     */
    public List<OutflowNoteEntity> search(String q, int limit) {
        String escaped = java.util.regex.Pattern.quote(q.trim());
        return coll().find(Filters.or(
                        Filters.regex("ref", escaped, "i"),
                        Filters.regex("dispatchNoteNumber", escaped, "i"),
                        Filters.regex("loadingNumber", escaped, "i"),
                        Filters.regex("truckNumber", escaped, "i"),
                        Filters.regex("customerName", escaped, "i"),
                        Filters.regex("destination", escaped, "i")))
                .sort(new Document("date", -1).append("ref", -1))
                .limit(limit)
                .into(new ArrayList<>());
    }

    public void insert(OutflowNoteEntity e) { coll().insertOne(e); }
}
