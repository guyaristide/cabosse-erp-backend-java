package com.ntech.cabosse.agriculture.harvest.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
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
public class HarvestRepository {

    public static final String COLLECTION = "harvests";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<HarvestEntity> coll() {
        return tenantDb.collection(COLLECTION, HarvestEntity.class);
    }

    public Optional<HarvestEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<HarvestEntity> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return coll().find(Filters.in("_id", ids)).into(new ArrayList<>());
    }

    public List<HarvestEntity> listByParcel(UUID parcelId) {
        return coll().find(Filters.eq("parcelId", parcelId))
                .sort(new Document("harvestDate", -1))
                .into(new ArrayList<>());
    }

    public long countSearch(UUID parcelId, UUID memberId, Integer campaignYear, String q) {
        return coll().countDocuments(searchFilter(parcelId, memberId, campaignYear, q));
    }

    public List<HarvestEntity> search(UUID parcelId, UUID memberId, Integer campaignYear,
                                      String q, int skip, int limit) {
        return coll().find(searchFilter(parcelId, memberId, campaignYear, q))
                .sort(new Document("harvestDate", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(UUID parcelId, UUID memberId, Integer campaignYear, String q) {
        List<Bson> filters = new ArrayList<>();
        if (parcelId != null) filters.add(Filters.eq("parcelId", parcelId));
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        if (campaignYear != null) filters.add(Filters.eq("campaignYear", campaignYear));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("code", escaped, "i"),
                    Filters.regex("parcelCode", escaped, "i"),
                    Filters.regex("memberName", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(HarvestEntity e) { coll().insertOne(e); }

    public void replace(HarvestEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
