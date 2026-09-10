package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 085 — référentiels des conditions de paiement
 * ({@code payment_terms}) et des certifications ({@code certifications}).
 *
 * <p>Remplacent la saisie libre du champ {@code paymentTerms} des
 * fournisseurs et bons de commande, et des certifications de parcelle.
 * Index unique sur {@code code}. <strong>Pas de seed</strong> : ces
 * valeurs sont propres au tenant et se construisent à l'usage (ajout
 * inline depuis les fiches).</p>
 */
@ChangeUnit(id = "create_referential_collections", order = "085", author = "neiba")
public class M085_CreateReferentialCollections {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("payment_terms").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_payment_terms_code")
        );
        database.getCollection("certifications").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_certifications_code")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("payment_terms").drop();
        database.getCollection("certifications").drop();
    }
}
