package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

/**
 * Migration 068 — statut sur les reçus d'achat producteur.
 *
 * <p>Le reçu est la seule voie d'entrée matière : il alimente le stock,
 * fixe le coût moyen et produit une écriture. Il n'avait pourtant ni
 * modification, ni suppression, ni contre-passation : une erreur de saisie
 * était définitive, et la retirer supposait une intervention en base sur
 * quatre collections.</p>
 *
 * <p>Les reçus existants sont actifs. Le champ est renseigné plutôt que
 * laissé absent : une requête d'agrégat se lit mieux sur un champ présent,
 * même si le code tolère l'absence pour les documents qu'une reprise
 * partielle aurait manqués.</p>
 */
@ChangeUnit(id = "producer_purchase_status", order = "068", author = "neiba")
public class M068_ProducerPurchaseStatus {

    private static final String COLLECTION = "producer_purchases";

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection(COLLECTION).updateMany(
                Filters.exists("status", false),
                Updates.set("status", "ACTIVE"));

        // Les cumuls (compte courant du délégué, reste à payer, rapports)
        // filtrent tous sur le statut : sans index, chacun balaie.
        database.getCollection(COLLECTION).createIndex(
                new Document("status", 1).append("date", 1),
                new IndexOptions().name("idx_producer_purchases_status_date"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).updateMany(
                Filters.exists("status", true),
                Updates.unset("status"));
    }
}
