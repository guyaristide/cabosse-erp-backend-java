package com.ntech.cabosse.intake.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class IntakeNoteRepository {

    public static final String COLLECTION = "intake_notes";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<IntakeNoteEntity> coll() {
        return tenantDb.collection(COLLECTION, IntakeNoteEntity.class);
    }

    public Optional<IntakeNoteEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<IntakeNoteEntity> findByRef(String ref) {
        return Optional.ofNullable(coll().find(Filters.eq("ref", ref)).first());
    }

    public List<IntakeNoteEntity> list(String status) {
        var filter = status == null || status.isBlank()
                ? new Document() : Filters.eq("status", status);
        return coll().find(filter)
                .sort(new Document("date", -1).append("ref", -1))
                .into(new ArrayList<>());
    }

    /**
     * Recherche libre pour la palette : le numéro du bordereau, le camion,
     * le fournisseur. Le bordereau est la pièce que le magasin et la
     * comptabilité se citent au téléphone, il doit se retrouver au numéro.
     */
    public List<IntakeNoteEntity> search(String q, int limit) {
        String escaped = java.util.regex.Pattern.quote(q.trim());
        return coll().find(Filters.or(
                        Filters.regex("ref", escaped, "i"),
                        Filters.regex("truckNumber", escaped, "i"),
                        Filters.regex("supplierName", escaped, "i"),
                        Filters.regex("supplierCode", escaped, "i")))
                .sort(new Document("date", -1).append("ref", -1))
                .limit(limit)
                .into(new ArrayList<>());
    }

    public void insert(IntakeNoteEntity e) { coll().insertOne(e); }

    /**
     * Corrige un bordereau, conditionné au statut : un bordereau déjà
     * comptabilisé a figé son écart et créé ses reçus, il ne bouge plus.
     *
     * @return {@code true} si la correction a été appliquée
     */
    public boolean correct(UUID id, IntakeNoteEntity values) {
        var result = coll().updateOne(
                Filters.and(Filters.eq("_id", id),
                        Filters.eq("status", IntakeNoteEntity.STATUS_TO_ACCOUNT)),
                Updates.combine(
                        Updates.set("date", values.date),
                        Updates.set("supplierName", values.supplierName),
                        Updates.set("delegateSupplierId", values.delegateSupplierId),
                        Updates.set("grossWeightKg", values.grossWeightKg),
                        Updates.set("bagCount", values.bagCount),
                        Updates.set("netWeightKg", values.netWeightKg),
                        Updates.set("siteId", values.siteId),
                        Updates.set("updatedAt", Instant.now())));
        return result.getModifiedCount() > 0;
    }

    /**
     * Supprime un bordereau, conditionné au statut : le constat n'a rien
     * écrit, l'effacer avant comptabilisation est sans effet de bord.
     *
     * @return {@code true} si la suppression a eu lieu
     */
    public boolean deleteIfToAccount(UUID id) {
        return coll().deleteOne(
                Filters.and(Filters.eq("_id", id),
                        Filters.eq("status", IntakeNoteEntity.STATUS_TO_ACCOUNT)))
                .getDeletedCount() > 0;
    }

    /**
     * Réserve la comptabilisation, conditionnée au statut : deux
     * comptabilisations concurrentes ne doivent pas créer deux fois les
     * reçus. La bascule a lieu <em>avant</em> la création — un échec en
     * cours de route laisse un bordereau comptabilisé aux totaux
     * partiels, visible, plutôt que des reçus en double invisibles.
     *
     * @return {@code true} si la réservation a eu lieu
     */
    public boolean claimAccounting(UUID id, String actorEmail) {
        var result = coll().updateOne(
                Filters.and(Filters.eq("_id", id),
                        Filters.eq("status", IntakeNoteEntity.STATUS_TO_ACCOUNT)),
                Updates.combine(
                        Updates.set("status", IntakeNoteEntity.STATUS_ACCOUNTED),
                        Updates.set("accountedAt", Instant.now()),
                        Updates.set("accountedByEmail", actorEmail),
                        Updates.set("updatedAt", Instant.now())));
        return result.getModifiedCount() > 0;
    }

    /**
     * Rouvre un bordereau dont la comptabilisation n'a produit aucun
     * reçu : la réservation est rendue, le comptable corrige et rejoue.
     */
    public void reopenAccounting(UUID id) {
        coll().updateOne(
                Filters.and(Filters.eq("_id", id),
                        Filters.eq("status", IntakeNoteEntity.STATUS_ACCOUNTED)),
                Updates.combine(
                        Updates.set("status", IntakeNoteEntity.STATUS_TO_ACCOUNT),
                        Updates.unset("accountedAt"),
                        Updates.unset("accountedByEmail"),
                        Updates.unset("accountedWeightKg"),
                        Updates.unset("accountedAmount"),
                        Updates.unset("receiptRefs"),
                        Updates.set("updatedAt", Instant.now())));
    }

    /** Pose les totaux et les références une fois les reçus créés. */
    public void finishAccounting(UUID id, BigDecimal weightKg, BigDecimal amount,
                                 List<String> receiptRefs) {
        coll().updateOne(Filters.eq("_id", id),
                Updates.combine(
                        Updates.set("accountedWeightKg", weightKg),
                        Updates.set("accountedAmount", amount),
                        Updates.set("receiptRefs", receiptRefs),
                        Updates.set("updatedAt", Instant.now())));
    }
}
