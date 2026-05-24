package com.ntech.cabosse.achats.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
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
public class PurchaseOrderRepository {

    public static final String COLLECTION = "purchase_orders";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<PurchaseOrderEntity> coll() {
        return tenantDb.collection(COLLECTION, PurchaseOrderEntity.class);
    }

    public List<PurchaseOrderEntity> listAll() {
        return coll().find()
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<PurchaseOrderEntity> search(BcStatus status, String q) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("supplierName", escaped, "i"),
                    Filters.regex("invoiceNumber", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public Optional<PurchaseOrderEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(PurchaseOrderEntity e) {
        coll().insertOne(e);
    }

    public void replace(PurchaseOrderEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
