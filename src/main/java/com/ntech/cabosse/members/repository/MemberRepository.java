package com.ntech.cabosse.members.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux membres-producteurs de la structure (tenant-scoped). */
@ApplicationScoped
public class MemberRepository {

    public static final String COLLECTION = "members";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<MemberEntity> coll() {
        return tenantDb.collection(COLLECTION, MemberEntity.class);
    }

    public Optional<MemberEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public Optional<MemberEntity> findBySupplierId(UUID supplierId) {
        return Optional.ofNullable(coll().find(Filters.eq("supplierId", supplierId)).first());
    }

    public List<MemberEntity> search(String q, MemberStatus statusFilter) {
        List<Bson> filters = new ArrayList<>();
        if (statusFilter != null) filters.add(Filters.eq("status", statusFilter.name()));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("code", escaped, "i"),
                    Filters.regex("name", escaped, "i"),
                    Filters.regex("village", escaped, "i"),
                    Filters.regex("phone", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("name", 1))
                .into(new ArrayList<>());
    }

    public long count() {
        return coll().countDocuments();
    }

    public void insert(MemberEntity e) { coll().insertOne(e); }

    public void replace(MemberEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
