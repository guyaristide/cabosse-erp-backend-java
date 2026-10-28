package com.ntech.cabosse.logs.repository;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import com.ntech.cabosse.shared.idempotency.IdempotencyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;

/**
 * Les journaux d'une structure lus pour l'archive, sur une base nommée.
 *
 * <p>La base s'ouvre par son nom et non par le contexte de la requête :
 * l'éditeur télécharge l'archive d'une autre structure que la sienne,
 * comme le diagnostic ouvre la base d'une structure par son nom. Les
 * documents sont lus bruts et triés du plus ancien au plus récent : une
 * archive se relit dans l'ordre où les choses se sont passées.</p>
 */
@ApplicationScoped
public class TenantLogsRepository {

    @Inject MongoClient mongoClient;

    public FindIterable<Document> importRuns(String databaseName, Instant from, Instant to) {
        return between(databaseName, ImportRunEntity.COLLECTION, "at", from, to);
    }

    public FindIterable<Document> notifications(String databaseName, Instant from, Instant to) {
        return between(databaseName, NotificationDeliveryRepository.COLLECTION, "createdAt", from, to);
    }

    /**
     * Les clés d'idempotence, sans la réponse rejouée : elle contient le
     * document métier entier, qui n'a rien à faire dans un journal.
     */
    public FindIterable<Document> idempotencyKeys(String databaseName, Instant from, Instant to) {
        return between(databaseName, IdempotencyRepository.COLLECTION, "createdAt", from, to)
                .projection(Projections.exclude("responseBody"));
    }

    private FindIterable<Document> between(String databaseName, String collection,
                                           String dateField, Instant from, Instant to) {
        return mongoClient.getDatabase(databaseName).getCollection(collection)
                .find(Filters.and(Filters.gte(dateField, from), Filters.lte(dateField, to)))
                .sort(Sorts.ascending(dateField));
    }
}
