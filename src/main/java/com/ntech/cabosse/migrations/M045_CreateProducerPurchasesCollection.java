package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 045 — reçus d'achat de matière première au producteur
 * ({@code producer_purchases}, backlog NEG-01).
 *
 * <p>Index unique sur {@code ref} ; index sur {@code memberId} et
 * {@code campaignYear} pour les listes et l'état de synthèse.</p>
 */
@ChangeUnit(id = "create_producer_purchases_collection", order = "045", author = "neiba")
public class M045_CreateProducerPurchasesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("producer_purchases");
        coll.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_producer_purchases_ref"));
        coll.createIndex(Indexes.ascending("memberId"),
                new IndexOptions().name("idx_producer_purchases_member"));
        coll.createIndex(Indexes.ascending("campaignYear"),
                new IndexOptions().name("idx_producer_purchases_campaign"));
        coll.createIndex(Indexes.descending("date"),
                new IndexOptions().name("idx_producer_purchases_date"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("producer_purchases").drop();
    }
}
