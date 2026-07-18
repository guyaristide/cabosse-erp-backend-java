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
 * Migration 033 — comptes des paramétrages comptables v7 (backlog
 * CPT-13) pour les tenants déjà seedés : TVA déductible subdivisée
 * (44566, nouveau défaut), souscription des parts sociales (461) et
 * capital souscrit (1018, valeur candidate de {@code memberCapitalAccount}
 * en cycle souscription). Le compte 4456 existant est conservé : les
 * pièces historiques y restent et la déclaration TVA agrège les deux.
 */
@ChangeUnit(id = "add_capital_and_vat_accounts", order = "033", author = "neiba")
public class M033_AddCapitalAndVatAccounts {

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        List<Document> additions = List.of(
                new Document("number", "44566").append("label", "État, TVA déductible sur achats").append("family", "TVA"),
                new Document("number", "461").append("label", "Associés, opérations sur le capital").append("family", "AUTRES"),
                new Document("number", "1018").append("label", "Capital souscrit — parts sociales").append("family", "AUTRES")
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
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // No-op assumé : les comptes ajoutés peuvent déjà porter des écritures.
    }
}
