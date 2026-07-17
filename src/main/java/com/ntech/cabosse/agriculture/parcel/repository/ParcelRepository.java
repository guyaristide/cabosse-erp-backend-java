package com.ntech.cabosse.agriculture.parcel.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux parcelles agricoles du tenant. */
@ApplicationScoped
public class ParcelRepository {

    public static final String COLLECTION = "parcels";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ParcelEntity> coll() {
        return tenantDb.collection(COLLECTION, ParcelEntity.class);
    }

    public Optional<ParcelEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public List<ParcelEntity> listByMember(UUID memberId) {
        return coll().find(Filters.eq("memberId", memberId))
                .sort(new Document("name", 1))
                .into(new ArrayList<>());
    }

    public long countSearch(String q, ParcelStatus statusFilter, UUID memberId) {
        return coll().countDocuments(searchFilter(q, statusFilter, memberId));
    }

    public List<ParcelEntity> search(String q, ParcelStatus statusFilter, UUID memberId,
                                     int skip, int limit) {
        return coll().find(searchFilter(q, statusFilter, memberId))
                .sort(new Document("name", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(String q, ParcelStatus statusFilter, UUID memberId) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("code", escaped, "i"),
                    Filters.regex("name", escaped, "i"),
                    Filters.regex("variety", escaped, "i"),
                    Filters.regex("memberName", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public void insert(ParcelEntity e) { coll().insertOne(e); }

    public void replace(ParcelEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
