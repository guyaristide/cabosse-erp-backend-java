package com.ntech.cabosse.producerpurchase.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class ProducerPurchaseRepository {

    public static final String COLLECTION = "producer_purchases";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ProducerPurchaseEntity> coll() {
        return tenantDb.collection(COLLECTION, ProducerPurchaseEntity.class);
    }

    private Bson searchFilter(String q, UUID campaignId, UUID memberId) {
        List<Bson> filters = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String escaped = Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("producerName", escaped, "i"),
                    Filters.regex("producerCode", escaped, "i"),
                    Filters.regex("producerExternalCode", escaped, "i")));
        }
        if (campaignId != null) filters.add(Filters.eq("campaignId", campaignId));
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(String q, UUID campaignId, UUID memberId) {
        return coll().countDocuments(searchFilter(q, campaignId, memberId));
    }

    public List<ProducerPurchaseEntity> search(String q, UUID campaignId, UUID memberId,
                                               int skip, int limit) {
        return coll().find(searchFilter(q, campaignId, memberId))
                .sort(new Document("date", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /** Tous les reçus filtrés (registre / état de synthèse, NEG-01). */
    /** Reçus portés par le compte courant d'un délégué, du plus ancien au plus récent. */
    /**
     * Écarte les reçus contre-passés.
     *
     * <p>Un reçu annulé reste au registre — on ne supprime rien — mais il
     * ne doit peser sur aucun cumul : ni compte courant du délégué, ni
     * reste à payer, ni rapport. Les documents antérieurs au champ n'ont
     * pas de statut : ils sont actifs.</p>
     */
    private static Bson notCancelled() {
        return Filters.or(
                Filters.eq("status", "ACTIVE"),
                Filters.exists("status", false));
    }

    public List<ProducerPurchaseEntity> listByDelegate(UUID delegateSupplierId, UUID campaignId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("delegateSupplierId", delegateSupplierId));
        filters.add(notCancelled());
        if (campaignId != null) filters.add(Filters.eq("campaignId", campaignId));
        return coll().find(Filters.and(filters))
                .sort(new Document("date", 1).append("ref", 1))
                .into(new ArrayList<>());
    }

    public List<ProducerPurchaseEntity> listAll(UUID campaignId) {
        return coll().find(Filters.and(searchFilter(null, campaignId, null), notCancelled()))
                .sort(new Document("date", 1)).into(new ArrayList<>());
    }

    /**
     * Reste à payer d'un reçu, exprimé en Mongo : montant dû, moins ce qui
     * a été retenu au titre des crédits, moins ce qui a déjà été versé.
     */
    private static Bson unpaidExpr() {
        return Filters.expr(new Document("$gt", List.of(
                new Document("$subtract", List.of(
                        new Document("$subtract", List.of(
                                new Document("$ifNull", List.of("$amountFcfa", 0)),
                                new Document("$ifNull", List.of("$creditImputedFcfa", 0)))),
                        new Document("$ifNull", List.of("$amountPaidFcfa", 0)))),
                0)));
    }

    /**
     * Livraisons non soldées, du plus ancien au plus récent : c'est
     * l'ordre dans lequel un fournisseur attend d'être payé.
     *
     * @param memberId   producteur bénéficiaire, ou {@code null}
     * @param delegateId délégué bénéficiaire, ou {@code null}
     */
    public List<ProducerPurchaseEntity> listUnpaid(UUID memberId, UUID delegateId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(unpaidExpr());
        filters.add(notCancelled());
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        if (delegateId != null) filters.add(Filters.eq("delegateSupplierId", delegateId));
        // Un reçu porté par un délégué se règle au délégué, pas au
        // producteur : sans ce filtre, la même somme apparaîtrait due deux
        // fois.
        if (memberId != null && delegateId == null) {
            filters.add(Filters.exists("delegateSupplierId", false));
        }
        return coll().find(Filters.and(filters))
                .sort(new Document("date", 1).append("ref", 1))
                .into(new ArrayList<>());
    }

    /** Toutes les livraisons non soldées, tous bénéficiaires confondus. */
    public List<ProducerPurchaseEntity> listAllUnpaid() {
        return coll().find(Filters.and(unpaidExpr(), notCancelled()))
                .sort(new Document("date", 1).append("ref", 1))
                .into(new ArrayList<>());
    }

    /**
     * Impute un règlement sur un reçu, sans jamais dépasser ce qui reste
     * dû. La condition est évaluée par Mongo dans la même opération que
     * l'incrément : deux règlements concurrents ne peuvent pas payer deux
     * fois la même livraison.
     *
     * @return {@code true} si le règlement a été appliqué
     */
    public boolean tryPay(UUID id, java.math.BigDecimal amount) {
        var result = coll().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.expr(new Document("$lte", List.of(
                                new Document("$add", List.of(
                                        new Document("$ifNull", List.of("$amountPaidFcfa", 0)),
                                        amount)),
                                new Document("$subtract", List.of(
                                        new Document("$ifNull", List.of("$amountFcfa", 0)),
                                        new Document("$ifNull", List.of("$creditImputedFcfa", 0)))))))),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.inc("amountPaidFcfa", amount),
                        com.mongodb.client.model.Updates.set("updatedAt", java.time.Instant.now())));
        return result.getModifiedCount() > 0;
    }

    /** Compensation d'un règlement après échec d'une étape ultérieure. */
    public void unpay(UUID id, java.math.BigDecimal amount) {
        coll().updateOne(Filters.eq("_id", id),
                com.mongodb.client.model.Updates.inc("amountPaidFcfa", amount.negate()));
    }

    public Optional<ProducerPurchaseEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Livraison portant déjà ce numéro de reçu officiel, s'il y en a une. */
    public Optional<ProducerPurchaseEntity> findByOfficialReceipt(String officialReceiptRef) {
        return Optional.ofNullable(
                coll().find(Filters.eq("officialReceiptRef", officialReceiptRef)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(ProducerPurchaseEntity e) { coll().insertOne(e); }

    /**
     * Passe le reçu à CANCELLED, une seule fois.
     *
     * <p>Écriture conditionnée sur l'état d'avant : deux annulations
     * simultanées ne peuvent pas défaire deux fois le stock et l'avance.
     * Un document antérieur au champ n'a pas de statut, d'où le
     * {@code exists false} accepté.</p>
     */
    public boolean tryCancel(UUID id) {
        var result = coll().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.or(
                                Filters.eq("status", "ACTIVE"),
                                Filters.exists("status", false))),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("status", "CANCELLED"),
                        com.mongodb.client.model.Updates.set("updatedAt", java.time.Instant.now())));
        return result.getModifiedCount() > 0;
    }

    public void replace(ProducerPurchaseEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    /**
     * Retire un reçu dont la création n'a pas abouti. Réservé à la
     * compensation : un reçu acquis ne se supprime pas, il se
     * contre-passe.
     */
    public void deleteById(UUID id) { coll().deleteOne(Filters.eq("_id", id)); }
}
