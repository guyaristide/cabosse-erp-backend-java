package com.ntech.cabosse.notification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationDeliveryEntity;
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

/**
 * File d'envoi du tenant.
 *
 * <p>La prise d'une ligne est un {@code findOneAndUpdate} conditionnel :
 * la même opération teste l'éligibilité et marque la ligne comme prise.
 * Deux instances qui drainent en parallèle ne peuvent donc pas envoyer
 * deux fois le même message. C'est la transposition Mongo du marquage
 * transactionnel employé ailleurs : ici, une lecture suivie d'une
 * réécriture serait à la fois inutile et dangereuse (conflit d'écriture
 * en production), la règle du projet est de passer par une mise à jour
 * atomique conditionnelle.</p>
 */
@ApplicationScoped
public class NotificationDeliveryRepository {

    public static final String COLLECTION = "notification_deliveries";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<NotificationDeliveryEntity> coll() {
        return tenantDb.collection(COLLECTION, NotificationDeliveryEntity.class);
    }

    public void insert(NotificationDeliveryEntity e) {
        coll().insertOne(e);
    }

    public Optional<NotificationDeliveryEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /**
     * Prend la prochaine ligne éligible d'un canal, ou rend vide s'il n'y
     * en a pas. Éligible : en attente et réarmée, ou prise depuis trop
     * longtemps par un envoyeur qui n'a pas survécu.
     *
     * <p>Le compteur de tentatives est incrémenté ici, à la prise : une
     * tentative qui laisse le processus mourir doit compter, sinon une
     * ligne qui fait tomber l'envoyeur serait reprise sans fin.</p>
     */
    public Optional<NotificationDeliveryEntity> claimNext(NotificationChannel channel,
                                                          Instant now,
                                                          java.time.Duration reclaimAfter) {
        Bson ready = Filters.and(
                Filters.eq("status", DeliveryStatus.PENDING.name()),
                Filters.or(
                        Filters.exists("nextAttemptAt", false),
                        Filters.lte("nextAttemptAt", now)));
        Bson abandoned = Filters.and(
                Filters.eq("status", DeliveryStatus.SENDING.name()),
                Filters.lt("claimedAt", now.minus(reclaimAfter)));

        Bson filter = Filters.and(
                Filters.eq("channel", channel.name()),
                Filters.or(ready, abandoned),
                Filters.or(
                        Filters.exists("expiresAt", false),
                        Filters.gt("expiresAt", now)));

        return Optional.ofNullable(coll().findOneAndUpdate(
                filter,
                Updates.combine(
                        Updates.set("status", DeliveryStatus.SENDING.name()),
                        Updates.set("claimedAt", now),
                        Updates.set("updatedAt", now),
                        Updates.inc("attempts", 1)),
                new FindOneAndUpdateOptions()
                        .sort(Sorts.ascending("createdAt"))
                        .returnDocument(ReturnDocument.AFTER)));
    }

    /** Marque une ligne partie. */
    public void markSent(UUID id, String providerCode, String providerMessageId, Instant now) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.set("status", DeliveryStatus.SENT.name()),
                Updates.set("providerCode", providerCode),
                Updates.set("providerMessageId", providerMessageId),
                Updates.set("sentAt", now),
                Updates.set("updatedAt", now),
                Updates.unset("lastError")));
    }

    /** Réarme une ligne pour une tentative ultérieure. */
    public void markRetry(UUID id, String providerCode, String reason,
                           Instant nextAttemptAt, Instant now) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.set("status", DeliveryStatus.PENDING.name()),
                Updates.set("providerCode", providerCode),
                Updates.set("lastError", reason),
                Updates.set("nextAttemptAt", nextAttemptAt),
                Updates.set("updatedAt", now),
                Updates.unset("claimedAt")));
    }

    /** Abandonne définitivement une ligne. */
    public void markFailed(UUID id, String providerCode, String reason, Instant now) {
        coll().updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.set("status", DeliveryStatus.FAILED.name()),
                Updates.set("providerCode", providerCode),
                Updates.set("lastError", reason),
                Updates.set("updatedAt", now),
                Updates.unset("claimedAt")));
    }

    /**
     * Périme les lignes dont la date limite est passée. Une file rallumée
     * après une panne longue ne doit pas déverser des alertes dont l'objet
     * n'existe plus.
     */
    public long expireOverdue(Instant now) {
        return coll().updateMany(
                Filters.and(
                        Filters.in("status",
                                DeliveryStatus.PENDING.name(), DeliveryStatus.SENDING.name()),
                        Filters.exists("expiresAt", true),
                        Filters.lte("expiresAt", now)),
                Updates.combine(
                        Updates.set("status", DeliveryStatus.EXPIRED.name()),
                        Updates.set("updatedAt", now),
                        Updates.unset("claimedAt"))
        ).getModifiedCount();
    }

    /** Y a-t-il quelque chose à drainer sur ce canal ? */
    public boolean hasWork(NotificationChannel channel, Instant now) {
        return coll().find(Filters.and(
                        Filters.eq("channel", channel.name()),
                        Filters.in("status",
                                DeliveryStatus.PENDING.name(), DeliveryStatus.SENDING.name()),
                        Filters.or(
                                Filters.exists("expiresAt", false),
                                Filters.gt("expiresAt", now))))
                .projection(new Document("_id", 1))
                .limit(1)
                .first() != null;
    }

    /** Journal filtrable, du plus récent au plus ancien. */
    public List<NotificationDeliveryEntity> search(NotificationChannel channel,
                                                    DeliveryStatus status,
                                                    Instant from, Instant to,
                                                    int limit, int skip) {
        List<Bson> filters = new ArrayList<>();
        if (channel != null) filters.add(Filters.eq("channel", channel.name()));
        if (status != null) filters.add(Filters.eq("status", status.name()));
        if (from != null) filters.add(Filters.gte("createdAt", from));
        if (to != null) filters.add(Filters.lte("createdAt", to));
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(Sorts.descending("createdAt"))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, Math.min(limit, 200)))
                .into(new ArrayList<>());
    }

    public long count(NotificationChannel channel, DeliveryStatus status) {
        List<Bson> filters = new ArrayList<>();
        if (channel != null) filters.add(Filters.eq("channel", channel.name()));
        if (status != null) filters.add(Filters.eq("status", status.name()));
        return coll().countDocuments(filters.isEmpty() ? new Document() : Filters.and(filters));
    }
}
