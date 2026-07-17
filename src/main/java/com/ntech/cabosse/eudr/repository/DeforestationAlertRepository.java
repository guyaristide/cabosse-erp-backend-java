package com.ntech.cabosse.eudr.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.eudr.entity.DeforestationAlertEntity;
import com.ntech.cabosse.eudr.entity.DeforestationAlertStatus;
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
public class DeforestationAlertRepository {

    public static final String COLLECTION = "deforestation_alerts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DeforestationAlertEntity> coll() {
        return tenantDb.collection(COLLECTION, DeforestationAlertEntity.class);
    }

    public Optional<DeforestationAlertEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<DeforestationAlertEntity> listByParcel(UUID parcelId) {
        return coll().find(Filters.eq("parcelId", parcelId))
                .sort(new Document("detectedAt", -1))
                .into(new ArrayList<>());
    }

    public long countSearch(DeforestationAlertStatus statusFilter, UUID parcelId) {
        return coll().countDocuments(searchFilter(statusFilter, parcelId));
    }

    public List<DeforestationAlertEntity> search(DeforestationAlertStatus statusFilter,
                                                 UUID parcelId, int skip, int limit) {
        return coll().find(searchFilter(statusFilter, parcelId))
                .sort(new Document("detectedAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(DeforestationAlertStatus statusFilter, UUID parcelId) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (parcelId != null) filters.add(Filters.eq("parcelId", parcelId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countNewSeverityHigh() {
        return coll().countDocuments(Filters.and(
                Filters.eq("status", DeforestationAlertStatus.NEW.name()),
                Filters.eq("severity", "HIGH")
        ));
    }

    public void insert(DeforestationAlertEntity e) { coll().insertOne(e); }

    public void replace(DeforestationAlertEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
