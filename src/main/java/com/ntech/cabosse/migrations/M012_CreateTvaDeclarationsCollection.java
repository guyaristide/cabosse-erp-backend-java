package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/** Migration 012 — collection {@code tva_declarations}. */
@ChangeUnit(id = "create_tva_declarations_collection", order = "012", author = "neiba")
public class M012_CreateTvaDeclarationsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("tva_declarations").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("yearMonth"),
                        new IndexOptions().unique(true).name("uniq_tva_yearMonth")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_tva_status")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("tva_declarations").drop();
    }
}
