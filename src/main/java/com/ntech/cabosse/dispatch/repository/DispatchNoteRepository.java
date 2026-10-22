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

    /**
     * Les sacs partis avant une date, sur un couple (site, article).
     *
     * <p>Un bordereau annulé ne compte pas : sa matière est revenue, ses
     * sacs aussi.</p>
     */
    public int sumBagsBefore(LocalDate date, UUID siteId, UUID articleId) {
        return sumBagsBetween(null, date, siteId, articleId);
    }

    /** Les sacs partis un jour donné, bornes [from, toExclusive[. */
    public int sumBagsBetween(LocalDate from, LocalDate toExclusive, UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("date", from));
        if (toExclusive != null) filters.add(Filters.lt("date", toExclusive));
        filters.add(Filters.ne("status", DispatchNoteStatus.CANCELLED.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        // Agrégation par les builders du driver : convertir le filtre en
        // document à la main perd le registre de codecs, et un UUID ne
        // sait plus s'encoder.
        Document sum = coll().withDocumentClass(Document.class).aggregate(List.of(
                com.mongodb.client.model.Aggregates.match(Filters.and(filters)),
                com.mongodb.client.model.Aggregates.group(null,
                        com.mongodb.client.model.Accumulators.sum("bags", "$totalBags"))
        ), Document.class).first();
        return sum != null && sum.get("bags") != null ? ((Number) sum.get("bags")).intValue() : 0;
    }

    public Optional<DispatchNoteEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /**
     * Le bordereau que porte un fichier d'import, par sa référence.
     *
     * <p>Sans casse : le magasinier recopie « bs-2026-0001 » aussi
     * souvent que « BS-2026-0001 », et ce n'est pas deux bordereaux.</p>
     */
    public Optional<DispatchNoteEntity> findByRef(String ref) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        return Optional.ofNullable(coll().find(Filters.regex("ref",
                "^" + java.util.regex.Pattern.quote(ref.trim()) + "$", "i")).first());
    }

    /**
     * Les bordereaux encore ouverts qui portent une marchandise donnée.
     *
     * <p>Sert à expliquer un « stock insuffisant » : quand les kilos
     * demandés sont déjà partis sur un chargement, le stock est
     * légitimement à zéro et l'erreur, exacte, n'apprend rien. Nommer
     * le bordereau dit où la marchandise est passée.</p>
     */
    public List<DispatchNoteEntity> listOpenFor(UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("status", DispatchNoteStatus.OPEN.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("date", -1))
                .limit(5)
                .into(new ArrayList<>());
    }

    /** Les colonnes sur lesquelles la liste accepte d'être triée. */
    public static final com.ntech.cabosse.shared.api.SortFields SORTABLE =
            com.ntech.cabosse.shared.api.SortFields.defaultSort("date", false)
                    .on("date")
                    .on("ref")
                    .on("customer", "customerName")
                    .on("weight", "totalNetKg")
                    .build();

    public List<DispatchNoteEntity> list(DispatchNoteStatus status, UUID siteId,
                                         int skip, int limit) {
        return list(status, siteId, skip, limit, SORTABLE.resolve(null));
    }

    public List<DispatchNoteEntity> list(DispatchNoteStatus status, UUID siteId,
                                         int skip, int limit,
                                         com.ntech.cabosse.shared.api.SortFields.Sort sort) {
        Document order = sort.toDocument();
        if (!order.containsKey("ref")) order.append("ref", sort.ascending() ? 1 : -1);
        List<Bson> filters = new ArrayList<>();
        if (status != null) filters.add(Filters.eq("status", status.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(order)
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
    /** Ajoute une pièce jointe à la liste, sans relire le document. */
    public void pushAttachment(UUID id, com.ntech.cabosse.shared.storage.AttachmentRef ref) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.push("attachments", ref),
                Updates.set("updatedAt", java.time.Instant.now())));
    }

    /** Retire une pièce jointe de la liste. */
    public void pullAttachment(UUID id, UUID fileId) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.pull("attachments", new org.bson.Document("fileId", fileId)),
                Updates.set("updatedAt", java.time.Instant.now())));
    }
}
