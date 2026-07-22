package com.ntech.cabosse.cacao.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.cacao.entity.SalesContractEntity;
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

    private Bson filter(Integer campaignYear, UUID customerId) {
        List<Bson> f = new ArrayList<>();
        if (campaignYear != null) f.add(Filters.eq("campaignYear", campaignYear));
        if (customerId != null) f.add(Filters.eq("customerId", customerId));
        return f.isEmpty() ? new Document() : Filters.and(f);
    }

    public List<SalesContractEntity> list(Integer campaignYear, UUID customerId) {
        return coll().find(filter(campaignYear, customerId))
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
