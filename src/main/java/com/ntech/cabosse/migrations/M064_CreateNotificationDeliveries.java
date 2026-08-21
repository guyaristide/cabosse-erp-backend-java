package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 064 — file d'envoi des notifications dans la base du tenant.
 *
 * <p>Trois index, un par usage réel :</p>
 * <ul>
 *   <li>la <strong>prise</strong> par le relais, qui cherche la plus
 *       ancienne ligne éligible d'un canal. Index partiel restreint aux
 *       lignes non conclues : une file saine est presque vide, l'index
 *       n'a aucune raison de porter l'historique des envois réussis ;</li>
 *   <li>le <strong>journal</strong> du back-office, filtré par canal et
 *       statut, trié du plus récent au plus ancien ;</li>
 *   <li>la recherche par <strong>objet métier</strong>, pour répondre à
 *       « qu'a-t-on envoyé au sujet de cette facture ».</li>
 * </ul>
 */
@ChangeUnit(id = "create_notification_deliveries", order = "064", author = "neiba")
public class M064_CreateNotificationDeliveries {

    static final String COLLECTION = "notification_deliveries";

    @Execution
    public void execute(MongoDatabase database) {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) { exists = true; break; }
        }
        if (!exists) database.createCollection(COLLECTION);

        database.getCollection(COLLECTION).createIndexes(List.of(
                new com.mongodb.client.model.IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("channel"),
                                Indexes.ascending("nextAttemptAt"),
                                Indexes.ascending("createdAt")),
                        new IndexOptions()
                                .name("idx_deliveries_claim")
                                .partialFilterExpression(
                                        Filters.in("status", "PENDING", "SENDING"))),
                new com.mongodb.client.model.IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("channel"),
                                Indexes.ascending("status"),
                                Indexes.descending("createdAt")),
                        new IndexOptions().name("idx_deliveries_journal")),
                new com.mongodb.client.model.IndexModel(
                        Indexes.ascending("subjectRef"),
                        new IndexOptions()
                                .name("idx_deliveries_subjectRef")
                                .partialFilterExpression(Filters.type("subjectRef", "string")))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).drop();
    }
}
