package com.ntech.cabosse.collector.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CollectorAdvanceRepository {

    public static final String COLLECTION = "collector_advances";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CollectorAdvanceEntity> coll() {
        return tenantDb.collection(COLLECTION, CollectorAdvanceEntity.class);
    }

    public Optional<CollectorAdvanceEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String status) {
        return (status == null || status.isBlank())
                ? new org.bson.Document() : Filters.eq("status", status);
    }

    /**
     * Toutes les avances d'un statut, sans pagination.
     *
     * <p>Sert la file de trésorerie, qui doit trier par ancienneté à
     * travers plusieurs sources : une pagination par source empêcherait
     * tout classement global. Le volume est borné par le statut, pas par
     * l'historique : ce qui est approuvé et pas encore décaissé se compte
     * en dizaines.</p>
     */
    public List<CollectorAdvanceEntity> findByStatus(String status) {
        return coll().find(Filters.eq("status", status))
                .sort(new org.bson.Document("advanceDate", 1))
                .into(new ArrayList<>());
    }

    /**
     * Les avances effectivement décaissées sur une période.
     *
     * <p>Sert l'état des règlements exécutés. Bornée par les dates, jamais
     * par le seul statut : l'historique d'une structure ne se charge pas
     * en mémoire, là où ce qui attend se compte en dizaines.</p>
     */
    public List<CollectorAdvanceEntity> findDisbursedBetween(
            java.time.LocalDate from, java.time.LocalDate to) {
        return coll().find(Filters.and(
                        Filters.ne("disbursedAt", null),
                        Filters.gte("advanceDate", from),
                        Filters.lte("advanceDate", to)))
                .sort(new org.bson.Document("advanceDate", -1))
                .into(new ArrayList<>());
    }

    public long countSearch(String status) {
        return coll().countDocuments(searchFilter(status));
    }

    public List<CollectorAdvanceEntity> search(String status, int skip, int limit) {
        return coll().find(searchFilter(status))
                .sort(new org.bson.Document("createdAt", -1))
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    public void insert(CollectorAdvanceEntity e) { coll().insertOne(e); }

    public void replace(CollectorAdvanceEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException(Messages.msg("m.col-advance-concurrent-update"));
        }
    }

    /** Avances ouvertes d'un délégué, la plus ancienne en tête. */
    public List<CollectorAdvanceEntity> listOpenByDelegate(UUID delegateSupplierId) {
        return coll().find(Filters.and(
                        Filters.eq("delegateSupplierId", delegateSupplierId),
                        Filters.eq("status", "OPEN")))
                .sort(new org.bson.Document("advanceDate", 1).append("createdAt", 1))
                .into(new ArrayList<>());
    }

    /**
     * Les avances <strong>décaissées</strong> d'un délégué, la plus récente
     * en tête. Ouvertes ou closes : dans les deux cas l'argent est sorti.
     *
     * <p>C'est la seule liste que le compte courant doit voir. Une demande
     * en attente ou refusée n'a rien remis au délégué : la compter
     * gonflerait son solde d'un argent qu'il n'a jamais eu, et la garde de
     * dette antérieure refuserait un refinancement sur une dette
     * imaginaire.</p>
     */
    public List<CollectorAdvanceEntity> listDisbursedByDelegate(UUID delegateSupplierId) {
        return coll().find(Filters.and(
                        Filters.eq("delegateSupplierId", delegateSupplierId),
                        Filters.in("status", "OPEN", "CLOSED")))
                .sort(new org.bson.Document("advanceDate", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    public Optional<CollectorAdvanceEntity> oldestOpenForDelegate(UUID delegateSupplierId) {
        List<CollectorAdvanceEntity> open = listOpenByDelegate(delegateSupplierId);
        return open.isEmpty() ? Optional.empty() : Optional.of(open.get(0));
    }

    /**
     * Impute atomiquement un montant sur l'avance : décrément conditionnel
     * en une seule opération {@code updateOne}, sans read-modify-replace qui
     * ouvrirait une course.
     *
     * <p>Aucun plafond : le solde peut devenir négatif. Le délégué livre
     * régulièrement plus que ce qu'il a reçu, et la coopérative lui doit
     * alors la différence jusqu'au décompte de fin de campagne. Refuser
     * l'imputation reviendrait à refuser un achat déjà payé au producteur.</p>
     */
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
                Updates.pull("attachments", new org.bson.Document("fileId", fileId)),
                Updates.set("updatedAt", Instant.now())));
    }

    public void impute(UUID id, BigDecimal amount) {
        coll().updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", "OPEN")),
                Updates.combine(
                        Updates.inc("remainingFcfa", amount.negate()),
                        Updates.inc("consumedAmountFcfa", amount),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
    }

    /**
     * Compensation d'une imputation (recrédit) après échec d'une étape
     * ultérieure du reçu. Best-effort, non conditionné au statut.
     */
    public void creditBack(UUID id, BigDecimal amount) {
        coll().updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.inc("remainingFcfa", amount),
                        Updates.inc("consumedAmountFcfa", amount.negate()),
                        Updates.inc("version", 1L),
                        Updates.set("updatedAt", Instant.now())));
    }
}
