package com.ntech.cabosse.dispatch.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.dispatch.entity.DispatchNoteEntity;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;
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

/** Accès aux bordereaux de sortie (CE-195). */
@ApplicationScoped
public class DispatchNoteRepository {

    public static final String COLLECTION = "dispatch_notes";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DispatchNoteEntity> coll() {
        return tenantDb.collection(COLLECTION, DispatchNoteEntity.class);
    }

    public Optional<DispatchNoteEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<DispatchNoteEntity> list(DispatchNoteStatus status, UUID siteId,
                                         int skip, int limit) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) filters.add(Filters.eq("status", status.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("date", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    public long count(DispatchNoteStatus status, UUID siteId) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) filters.add(Filters.eq("status", status.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        return coll().countDocuments(filters.isEmpty() ? new Document() : Filters.and(filters));
    }

    /** Les bordereaux d'une journée sur un site, dans l'ordre de saisie. */
    public List<DispatchNoteEntity> listByDateAndSite(LocalDate date, UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("date", date));
        filters.add(Filters.ne("status", DispatchNoteStatus.CANCELLED.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("createdAt", 1))
                .into(new ArrayList<>());
    }

    /**
     * Marque le bordereau vendu, une seule fois : l'update conditionnel
     * ferme la course entre deux ventes qui appelleraient le même
     * chargement.
     */
    public boolean tryMarkSold(UUID id, UUID saleId, String saleRef) {
        return coll().updateOne(
                Filters.and(Filters.eq("_id", id),
                        Filters.eq("status", DispatchNoteStatus.OPEN.name())),
                Updates.combine(
                        Updates.set("status", DispatchNoteStatus.SOLD.name()),
                        Updates.set("saleId", saleId),
                        Updates.set("saleRef", saleRef)))
                .getModifiedCount() == 1;
    }

    /** Défait le marquage vendu, quand l'écriture de la vente a échoué. */
    public void unmarkSold(UUID id) {
        coll().updateOne(Filters.eq("_id", id),
                Updates.combine(
                        Updates.set("status", DispatchNoteStatus.OPEN.name()),
                        Updates.unset("saleId"),
                        Updates.unset("saleRef")));
    }

    public void insert(DispatchNoteEntity e) {
        coll().insertOne(e);
    }

    public void replace(DispatchNoteEntity e) {
        e.version++;
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
