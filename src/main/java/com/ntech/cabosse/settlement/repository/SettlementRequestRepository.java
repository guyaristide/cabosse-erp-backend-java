package com.ntech.cabosse.settlement.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.settlement.entity.SettlementRequestEntity;
import com.ntech.cabosse.settlement.entity.SettlementRequestStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SettlementRequestRepository {

    public static final String COLLECTION = SettlementRequestEntity.COLLECTION;

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SettlementRequestEntity> coll() {
        return tenantDb.collection(COLLECTION, SettlementRequestEntity.class);
    }

    public Optional<SettlementRequestEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<SettlementRequestEntity> search(String status, int skip, int limit) {
        Bson filter = status == null || status.isBlank()
                ? new Document() : Filters.eq("status", status);
        return coll().find(filter)
                .sort(new Document("requestedAt", -1))
                .skip(Math.max(0, skip)).limit(Math.max(1, limit))
                .into(new ArrayList<>());
    }

    public long countSearch(String status) {
        Bson filter = status == null || status.isBlank()
                ? new Document() : Filters.eq("status", status);
        return coll().countDocuments(filter);
    }

    public List<SettlementRequestEntity> findByStatus(SettlementRequestStatus status) {
        return coll().find(Filters.eq("status", status.name()))
                .sort(new Document("requestedAt", 1))
                .into(new ArrayList<>());
    }

    /**
     * L'approbation en cours d'un bénéficiaire, s'il y en a une.
     *
     * <p>Une seule à la fois : deux demandes ouvertes sur la même
     * personne feraient sortir deux fois le même dû.</p>
     */
    public Optional<SettlementRequestEntity> findOpenFor(UUID memberId, UUID delegateSupplierId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.in("status",
                SettlementRequestStatus.PENDING_APPROVAL.name(),
                SettlementRequestStatus.APPROVED.name()));
        filters.add(delegateSupplierId != null
                ? Filters.eq("delegateSupplierId", delegateSupplierId)
                : Filters.eq("memberId", memberId));
        return Optional.ofNullable(coll().find(Filters.and(filters)).first());
    }

    public void insert(SettlementRequestEntity e) {
        coll().insertOne(e);
    }

    /**
     * Change le statut si, et seulement si, il est encore celui qu'on a
     * lu. Deux approbateurs qui tranchent en même temps ne peuvent pas
     * décider deux fois de la même demande.
     */
    public boolean transition(UUID id, SettlementRequestStatus from,
                              SettlementRequestStatus to, Bson changes) {
        var result = coll().updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", from.name())),
                Updates.combine(
                        Updates.set("status", to.name()),
                        Updates.set("updatedAt", Instant.now()),
                        Updates.inc("version", 1L),
                        changes));
        return result.getModifiedCount() == 1;
    }
}
