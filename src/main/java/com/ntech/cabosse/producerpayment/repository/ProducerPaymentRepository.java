package com.ntech.cabosse.producerpayment.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProducerPaymentRepository {

    public static final String COLLECTION = "producer_payments";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ProducerPaymentEntity> coll() {
        return tenantDb.collection(COLLECTION, ProducerPaymentEntity.class);
    }

    public Optional<ProducerPaymentEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(LocalDate from, LocalDate to, UUID memberId, UUID delegateId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("date", from));
        if (to != null) filters.add(Filters.lte("date", to));
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        if (delegateId != null) filters.add(Filters.eq("delegateSupplierId", delegateId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(LocalDate from, LocalDate to, UUID memberId, UUID delegateId) {
        return coll().countDocuments(searchFilter(from, to, memberId, delegateId));
    }

    public List<ProducerPaymentEntity> search(LocalDate from, LocalDate to, UUID memberId,
                                              UUID delegateId, int skip, int limit) {
        return coll().find(searchFilter(from, to, memberId, delegateId))
                .sort(new Document("date", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /** Règlements ayant touché une livraison : son historique de paiement. */
    public List<ProducerPaymentEntity> listForPurchase(UUID purchaseId) {
        return coll().find(Filters.eq("allocations.purchaseId", purchaseId))
                .sort(new Document("date", 1))
                .into(new ArrayList<>());
    }

    /** Règlements versés à un délégué, pour son compte courant. */
    public List<ProducerPaymentEntity> listForDelegate(UUID delegateSupplierId) {
        return coll().find(Filters.eq("delegateSupplierId", delegateSupplierId))
                .sort(new Document("date", 1))
                .into(new ArrayList<>());
    }

    public void insert(ProducerPaymentEntity e) { coll().insertOne(e); }
}
