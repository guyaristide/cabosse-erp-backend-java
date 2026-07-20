package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 038 — demandes d'achat ({@code purchase_requests}, backlog
 * ACH-01). Index unique sur la référence, index sur le statut pour la
 * liste filtrée.
 */
@ChangeUnit(id = "create_purchase_requests_collection", order = "038", author = "neiba")
public class M038_CreatePurchaseRequestsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("purchase_requests");
        coll.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_purchase_requests_ref"));
        coll.createIndex(Indexes.ascending("status"),
                new IndexOptions().name("idx_purchase_requests_status"));
        coll.createIndex(Indexes.descending("createdAt"),
                new IndexOptions().name("idx_purchase_requests_created"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("purchase_requests").drop();
    }
}
