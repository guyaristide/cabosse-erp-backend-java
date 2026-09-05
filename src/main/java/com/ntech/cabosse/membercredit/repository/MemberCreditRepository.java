package com.ntech.cabosse.membercredit.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
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

    /**
     * Tous les crédits d'un statut, sans pagination. Même raison que côté
     * avances : la file de trésorerie trie par ancienneté à travers
     * plusieurs sources.
     */
    public List<MemberCreditEntity> findByStatus(String status) {
        return coll().find(Filters.eq("status", status))
                .sort(new Document("requestedAt", 1))
                .into(new ArrayList<>());
    }

    public long countSearch(UUID memberId, String status, UUID campaignId) {
        return coll().countDocuments(searchFilter(memberId, status, campaignId));
    }

    /**
     * Les crédits effectivement décaissés sur une période.
     *
     * <p>Bornée par les dates : l'état des règlements est un historique,
     * et un historique ne se charge pas en entier.</p>
     */
    public List<MemberCreditEntity> findDisbursedBetween(
            java.time.LocalDate from, java.time.LocalDate to) {
        return coll().find(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.gte("disbursedAt", from),
                        com.mongodb.client.model.Filters.lte("disbursedAt", to)))
                .sort(new org.bson.Document("disbursedAt", -1))
                .into(new java.util.ArrayList<>());
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
    /** Crédits portant une retenue au titre d'un reçu donné. */
    public List<MemberCreditEntity> findImputedByPurchase(UUID purchaseId) {
        return coll().find(Filters.eq("imputations.purchaseId", purchaseId))
                .into(new ArrayList<>());
    }

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
            throw new ConflictException(Messages.msg("m.mcr-concurrent-update"));
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
                        Filters.gte("remaining", amount)),
                Updates.combine(
                        Updates.inc("remaining", amount.negate()),
                        Updates.inc("imputedAmount", amount),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
        return result.getModifiedCount() > 0;
    }

    /** Compensation d'une retenue après échec d'une étape ultérieure. */
    public void creditBack(UUID id, BigDecimal amount) {
        coll().updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.inc("remaining", amount),
                        Updates.inc("imputedAmount", amount.negate()),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
    }

    /**
     * Ajoute une pièce jointe. Poussée atomiquement : deux dépôts
     * simultanés ne doivent pas s'écraser l'un l'autre.
     */
    public void pushAttachment(UUID id, com.ntech.cabosse.shared.storage.AttachmentRef ref) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.push("attachments", ref),
                Updates.set("updatedAt", Instant.now())));
    }

    /** Retire une pièce jointe de la liste. */
    public void pullAttachment(UUID id, UUID fileId) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.pull("attachments", new Document("fileId", fileId)),
                Updates.set("updatedAt", Instant.now())));
    }

    /**
     * Retire la retenue liée à un reçu annulé et rouvre le crédit.
     *
     * <p>Le solde est recrédité à part, par {@link #creditBack}. Ici on
     * retire la ligne du journal et, si le crédit avait été soldé par
     * cette retenue, on le rouvre : un crédit soldé par une opération qui
     * n'existe plus ne l'est pas.</p>
     */
    public void removeImputationForPurchase(UUID id, UUID purchaseId) {
        coll().updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.pull("imputations", new Document("purchaseId", purchaseId)),
                        Updates.set("updatedAt", Instant.now())));
        coll().updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", "SETTLED")),
                Updates.combine(
                        Updates.set("status", "DISBURSED"),
                        Updates.unset("settledAt"),
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
