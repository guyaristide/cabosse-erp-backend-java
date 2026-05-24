package com.ntech.cabosse.reception.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
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
public class DirectReceiptRepository {

    public static final String COLLECTION = "direct_receipts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DirectReceiptEntity> coll() {
        return tenantDb.collection(COLLECTION, DirectReceiptEntity.class);
    }

    public List<DirectReceiptEntity> listAll() {
        return coll().find()
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<DirectReceiptEntity> search(DirectReceiptStatus status, String q) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("articleName", escaped, "i"),
                    Filters.regex("deliveryNoteRef", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public Optional<DirectReceiptEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(DirectReceiptEntity e) {
        coll().insertOne(e);
    }

    public void replace(DirectReceiptEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
