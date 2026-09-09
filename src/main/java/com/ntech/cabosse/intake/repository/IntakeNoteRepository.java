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

    public void insert(IntakeNoteEntity e) { coll().insertOne(e); }

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
