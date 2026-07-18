package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Migration 032 — alignement du plan comptable sur les jeux d'écritures
 * v7 : caisse en 571 (57x AUDCIF) et stocks de marchandises en 31/6031.
 *
 * <p>Ajoute les comptes manquants aux tenants déjà seedés par M011 (571,
 * 31, 6031). Le compte 530 existant est conservé et relabellisé « ancien
 * compte » : les pièces historiques y restent, la reprise de solde vers
 * 571 se fait par OD de reclassement.</p>
 */
@ChangeUnit(id = "align_chart_accounts_v7", order = "032", author = "neiba")
public class M032_AlignChartAccountsV7 {

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        List<Document> additions = List.of(
                new Document("number", "571").append("label", "Caisse").append("family", "TRESORERIE"),
                new Document("number", "31").append("label", "Stocks de marchandises").append("family", "AUTRES"),
                new Document("number", "6031").append("label", "Variation des stocks de marchandises").append("family", "CHARGES")
        );
        for (Document seed : additions) {
            boolean exists = coll.countDocuments(Filters.eq("number", seed.getString("number"))) > 0;
            if (!exists) {
                seed.put("_id", UuidCreator.getTimeOrderedEpoch());
                seed.put("active", true);
                seed.put("system", true);
                seed.put("createdAt", now);
                seed.put("updatedAt", now);
                coll.insertOne(seed);
            }
        }
        // 530 : plus jamais alimenté automatiquement — signalé dans le libellé.
        coll.updateOne(
                Filters.and(Filters.eq("number", "530"), Filters.eq("label", "Caisse")),
                new Document("$set", new Document("label", "Caisse (ancien compte, remplacé par 571)")
                        .append("updatedAt", now))
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Pas de suppression : les comptes ajoutés peuvent déjà porter des
        // écritures. Le rollback est un no-op assumé.
    }
}
