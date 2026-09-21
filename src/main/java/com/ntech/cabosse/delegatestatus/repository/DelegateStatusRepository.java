package com.ntech.cabosse.delegatestatus.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.delegatestatus.entity.DelegateStatusEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Accès au référentiel des positions de délégué. */
@ApplicationScoped
public class DelegateStatusRepository {

    public static final String COLLECTION = "delegate_statuses";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DelegateStatusEntity> coll() {
        return tenantDb.collection(COLLECTION, DelegateStatusEntity.class);
    }

    public List<DelegateStatusEntity> listAll() {
        return coll().find().sort(new Document("sortOrder", 1)).into(new ArrayList<>());
    }

    public Optional<DelegateStatusEntity> findById(UUID id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<DelegateStatusEntity> findByCode(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.eq("code", code)).first());
    }

    /** Index par identifiant, pour éviter une lecture par délégué. */
    public Map<UUID, DelegateStatusEntity> byId() {
        Map<UUID, DelegateStatusEntity> map = new LinkedHashMap<>();
        for (DelegateStatusEntity e : listAll()) map.put(e.id, e);
        return map;
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public void insert(DelegateStatusEntity e) {
        coll().insertOne(e);
    }

    /**
     * Écriture ciblée sur les seuls champs éditables.
     *
     * <p>Un {@code replaceOne} sur le document entier rejouerait le motif
     * lire-modifier-réécrire que le projet a déjà payé une fois, en
     * écrasant l'écriture d'un autre poste sans le dire.</p>
     */
    public void updateEditable(UUID id, String label, boolean warning, int sortOrder, boolean active) {
        coll().updateOne(Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("label", label)
                        .append("warning", warning)
                        .append("sortOrder", sortOrder)
                        .append("active", active)
                        .append("updatedAt", Instant.now())));
    }
}
