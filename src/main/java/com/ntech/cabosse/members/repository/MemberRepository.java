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

    /** Tous les membres du tenant, triés par nom (registre producteurs, REG-01). */
    public List<MemberEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new java.util.ArrayList<>());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public Optional<MemberEntity> findBySupplierId(UUID supplierId) {
        return Optional.ofNullable(coll().find(Filters.eq("supplierId", supplierId)).first());
    }

    public long countSearch(String q, MemberStatus statusFilter) {
        return coll().countDocuments(searchFilter(q, statusFilter));
    }

    public List<MemberEntity> search(String q, MemberStatus statusFilter, int skip, int limit) {
        return coll().find(searchFilter(q, statusFilter))
                .sort(new Document("name", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(String q, MemberStatus statusFilter) {
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
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long count() {
        return coll().countDocuments();
    }

    /** Producteurs portant ce numéro normalisé. Doit rester vide ou singleton. */
    public List<MemberEntity> findByProducerRefKey(String normalizedNumber) {
        if (normalizedNumber == null || normalizedNumber.isBlank()) return List.of();
        return coll().find(Filters.eq("producerRefKeys", normalizedNumber))
                .into(new java.util.ArrayList<>());
    }

    /** Producteurs portant au moins une pièce de ce type. */
    public List<MemberEntity> findByDocumentType(String typeName) {
        if (typeName == null || typeName.isBlank()) return List.of();
        return coll().find(Filters.eq("identityDocuments.type", typeName))
                .into(new java.util.ArrayList<>());
    }

    /** Écriture ciblée des clés de rapprochement (resynchronisation d'un type). */
    public void updateProducerRefKeys(MemberEntity e) {
        coll().updateOne(Filters.eq("_id", e.id),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("producerRefKeys", e.producerRefKeys),
                        com.mongodb.client.model.Updates.set("identityDocuments", e.identityDocuments)));
    }

    public void insert(MemberEntity e) { coll().insertOne(e); }

    public void replace(MemberEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
