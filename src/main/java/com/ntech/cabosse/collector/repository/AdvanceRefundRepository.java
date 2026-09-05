package com.ntech.cabosse.collector.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.collector.entity.AdvanceRefundEntity;
import com.ntech.cabosse.collector.entity.AdvanceRefundStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux règlements de reliquat d'avance (CE-187). */
@ApplicationScoped
public class AdvanceRefundRepository {

    public static final String COLLECTION = "advance_refunds";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<AdvanceRefundEntity> coll() {
        return tenantDb.collection(COLLECTION, AdvanceRefundEntity.class);
    }

    public Optional<AdvanceRefundEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Du plus récent au plus ancien, l'attente d'abord n'étant pas un tri. */
    public List<AdvanceRefundEntity> list(AdvanceRefundStatus status, int skip, int limit) {
        Bson filter = status != null
                ? Filters.eq("status", status.name())
                : new Document();
        return coll().find(filter)
                .sort(new Document("requestedAt", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    public long count(AdvanceRefundStatus status) {
        Bson filter = status != null
                ? Filters.eq("status", status.name())
                : new Document();
        return coll().countDocuments(filter);
    }

    /**
     * Une seule demande en cours par délégué : deux demandes ouvertes sur
     * le même compte sortiraient deux fois le même reliquat.
     */
    public boolean hasOpenRequest(UUID delegateSupplierId) {
        return coll().countDocuments(Filters.and(
                Filters.eq("delegateSupplierId", delegateSupplierId),
                Filters.in("status",
                        AdvanceRefundStatus.PENDING_APPROVAL.name(),
                        AdvanceRefundStatus.APPROVED.name()))) > 0;
    }

    /**
     * Total des reliquats payés à un délégué (campagne donnée, ou tout).
     * Des fonds sortis vers lui : le compte courant les compte comme une
     * avance, sans quoi un solde réglé resterait créditeur et pourrait se
     * régler deux fois.
     */
    public java.math.BigDecimal sumPaid(UUID delegateSupplierId, UUID campaignId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("delegateSupplierId", delegateSupplierId));
        filters.add(Filters.eq("status", AdvanceRefundStatus.PAID.name()));
        if (campaignId != null) filters.add(Filters.eq("campaignId", campaignId));
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (AdvanceRefundEntity e : coll().find(Filters.and(filters))) {
            java.math.BigDecimal paid = e.effectiveAmount();
            if (paid != null) total = total.add(paid);
        }
        return total;
    }

    public void insert(AdvanceRefundEntity e) {
        coll().insertOne(e);
    }

    public void replace(AdvanceRefundEntity e) {
        e.version++;
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
