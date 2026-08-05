package com.ntech.cabosse.membercredit.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MemberCreditRepository {

    public static final String COLLECTION = "member_credits";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<MemberCreditEntity> coll() {
        return tenantDb.collection(COLLECTION, MemberCreditEntity.class);
    }

    public Optional<MemberCreditEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(UUID memberId, String status, UUID campaignId) {
        List<Bson> filters = new ArrayList<>();
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        if (status != null && !status.isBlank()) filters.add(Filters.eq("status", status));
        if (campaignId != null) filters.add(Filters.eq("campaignId", campaignId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(UUID memberId, String status, UUID campaignId) {
        return coll().countDocuments(searchFilter(memberId, status, campaignId));
    }

    public List<MemberCreditEntity> search(UUID memberId, String status, UUID campaignId,
                                           int skip, int limit) {
        return coll().find(searchFilter(memberId, status, campaignId))
                .sort(new Document("requestedAt", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /**
     * Engagements décaissés d'un producteur qui restent à rembourser, du
     * plus ancien au plus récent : c'est l'ordre dans lequel un gérant
     * propose naturellement de retenir.
     */
    public List<MemberCreditEntity> outstandingForMember(UUID memberId) {
        return coll().find(Filters.and(
                        Filters.eq("memberId", memberId),
                        Filters.eq("status", "DISBURSED")))
                .sort(new Document("disbursedAt", 1).append("ref", 1))
                .into(new ArrayList<>());
    }

    public void insert(MemberCreditEntity e) { coll().insertOne(e); }

    public void replace(MemberCreditEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException("Le crédit a été modifié entre-temps. Rechargez la page.");
        }
    }

    /**
     * Retenue atomique : décrémente le reste dû en une seule opération
     * conditionnée au statut et au solde disponible. Un read-modify-replace
     * laisserait deux livraisons concurrentes retenir plus que le solde.
     *
     * @return {@code true} si la retenue a été appliquée
     */
    public boolean tryImpute(UUID id, BigDecimal amount) {
        var result = coll().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.eq("status", "DISBURSED"),
                        Filters.gte("remainingFcfa", amount)),
                Updates.combine(
                        Updates.inc("remainingFcfa", amount.negate()),
                        Updates.inc("imputedAmountFcfa", amount),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
        return result.getModifiedCount() > 0;
    }

    /** Compensation d'une retenue après échec d'une étape ultérieure. */
    public void creditBack(UUID id, BigDecimal amount) {
        coll().updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.inc("remainingFcfa", amount),
                        Updates.inc("imputedAmountFcfa", amount.negate()),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
    }

    /** Enregistre la retenue dans le journal du crédit et solde s'il y a lieu. */
    public void appendImputation(UUID id, MemberCreditEntity.Imputation imputation, boolean settled) {
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.push("imputations", imputation));
        updates.add(Updates.set("updatedAt", Instant.now()));
        if (settled) {
            updates.add(Updates.set("status", "SETTLED"));
            updates.add(Updates.set("settledAt", Instant.now()));
        }
        coll().updateOne(Filters.eq("_id", id), Updates.combine(updates));
    }
}
