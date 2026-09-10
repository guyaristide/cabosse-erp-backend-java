package com.ntech.cabosse.paymentterm.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.paymentterm.entity.PaymentTermEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class PaymentTermRepository {

    public static final String COLLECTION = "payment_terms";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<PaymentTermEntity> coll() {
        return tenantDb.collection(COLLECTION, PaymentTermEntity.class);
    }

    public List<PaymentTermEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new ArrayList<>());
    }

    public Optional<PaymentTermEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Sans casse : « 30J-FDM » et « 30j-fdm » sont le même code. */
    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.regex("code",
                "^" + Pattern.quote(code) + "$", "i")) > 0;
    }

    public void insert(PaymentTermEntity e) { coll().insertOne(e); }

    public void replace(PaymentTermEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new Document("$set", new Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
