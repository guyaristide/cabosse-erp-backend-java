package com.ntech.cabosse.eudr.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.eudr.entity.EudrDossierEntity;
import com.ntech.cabosse.eudr.entity.EudrStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class EudrDossierRepository {

    public static final String COLLECTION = "eudr_dossiers";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<EudrDossierEntity> coll() {
        return tenantDb.collection(COLLECTION, EudrDossierEntity.class);
    }

    public Optional<EudrDossierEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<EudrDossierEntity> findByParcel(UUID parcelId) {
        return Optional.ofNullable(coll().find(Filters.eq("parcelId", parcelId)).first());
    }

    public List<EudrDossierEntity> findByParcels(List<UUID> parcelIds) {
        if (parcelIds == null || parcelIds.isEmpty()) return List.of();
        return coll().find(Filters.in("parcelId", parcelIds))
                .into(new ArrayList<>());
    }

    /** Liste complète (agrégation des déclarations de diligence) — la liste UI passe par la variante paginée. */
    public List<EudrDossierEntity> search(EudrStatus statusFilter, String q) {
        return coll().find(searchFilter(statusFilter, q))
                .sort(new Document("parcelCode", 1))
                .into(new ArrayList<>());
    }

    public long countSearch(EudrStatus statusFilter, String q) {
        return coll().countDocuments(searchFilter(statusFilter, q));
    }

    public List<EudrDossierEntity> search(EudrStatus statusFilter, String q, int skip, int limit) {
        return coll().find(searchFilter(statusFilter, q))
                .sort(new Document("parcelCode", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(EudrStatus statusFilter, String q) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("parcelCode", escaped, "i"),
                    Filters.regex("parcelName", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(EudrDossierEntity e) { coll().insertOne(e); }

    public void replace(EudrDossierEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
