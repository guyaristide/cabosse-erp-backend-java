package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 039 — sections de collecte ({@code sections}) et avances aux
 * délégués ({@code collector_advances}, backlog ACH-02).
 */
@ChangeUnit(id = "create_collector_collections", order = "039", author = "neiba")
public class M039_CreateCollectorCollections {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("sections").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_sections_code"));
        var advances = database.getCollection("collector_advances");
        advances.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_collector_advances_ref"));
        advances.createIndex(Indexes.ascending("status"),
                new IndexOptions().name("idx_collector_advances_status"));
        advances.createIndex(Indexes.descending("createdAt"),
                new IndexOptions().name("idx_collector_advances_created"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("sections").drop();
        database.getCollection("collector_advances").drop();
    }
}
