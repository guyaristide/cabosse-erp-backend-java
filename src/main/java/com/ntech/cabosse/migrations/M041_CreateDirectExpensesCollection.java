package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 041 — dépenses directes sans bon de livraison
 * ({@code direct_expenses}, backlog ACH-03 : contrat/abonnement et petite
 * caisse).
 */
@ChangeUnit(id = "create_direct_expenses_collection", order = "041", author = "neiba")
public class M041_CreateDirectExpensesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        var expenses = database.getCollection("direct_expenses");
        expenses.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_direct_expenses_ref"));
        expenses.createIndex(Indexes.ascending("kind"),
                new IndexOptions().name("idx_direct_expenses_kind"));
        expenses.createIndex(Indexes.descending("createdAt"),
                new IndexOptions().name("idx_direct_expenses_created"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("direct_expenses").drop();
    }
}
