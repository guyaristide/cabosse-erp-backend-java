package com.ntech.cabosse.commodity.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.commodity.entity.SalesContractEntity;
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
public class SalesContractRepository {

    public static final String COLLECTION = "sales_contracts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SalesContractEntity> coll() {
        return tenantDb.collection(COLLECTION, SalesContractEntity.class);
    }

    private Bson filter(UUID campaignId, UUID customerId) {
        List<Bson> f = new ArrayList<>();
        if (campaignId != null) f.add(Filters.eq("campaignId", campaignId));
        if (customerId != null) f.add(Filters.eq("customerId", customerId));
        return f.isEmpty() ? new Document() : Filters.and(f);
    }

    public List<SalesContractEntity> list(UUID campaignId, UUID customerId) {
        return coll().find(filter(campaignId, customerId))
                .sort(new Document("createdAt", -1)).into(new ArrayList<>());
    }

    public Optional<SalesContractEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(SalesContractEntity e) { coll().insertOne(e); }

    public void replace(SalesContractEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
