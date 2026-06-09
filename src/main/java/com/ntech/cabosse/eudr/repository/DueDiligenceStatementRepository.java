package com.ntech.cabosse.eudr.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.eudr.entity.DueDiligenceStatementEntity;
import com.ntech.cabosse.eudr.entity.DueDiligenceStatus;
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
public class DueDiligenceStatementRepository {

    public static final String COLLECTION = "due_diligence_statements";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DueDiligenceStatementEntity> coll() {
        return tenantDb.collection(COLLECTION, DueDiligenceStatementEntity.class);
    }

    public Optional<DueDiligenceStatementEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<DueDiligenceStatementEntity> findBySale(UUID saleId) {
        return Optional.ofNullable(coll().find(Filters.eq("saleId", saleId)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public List<DueDiligenceStatementEntity> search(DueDiligenceStatus statusFilter) {
        Bson filter = statusFilter != null
                ? Filters.eq("status", statusFilter.name())
                : new Document();
        return coll().find(filter)
                .sort(new Document("generatedAt", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    public void insert(DueDiligenceStatementEntity e) { coll().insertOne(e); }

    public void replace(DueDiligenceStatementEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
