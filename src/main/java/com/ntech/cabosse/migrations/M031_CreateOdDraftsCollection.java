package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 031 — brouillons d'opérations diverses ({@code od_drafts}).
 *
 * <p>Index composé {@code (status, date)} : contrôle de clôture (« aucune
 * OD en brouillard sur la période ») et liste filtrée par statut.</p>
 */
@ChangeUnit(id = "create_od_drafts_collection", order = "031", author = "neiba")
public class M031_CreateOdDraftsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("od_drafts").createIndex(
                Indexes.ascending("status", "date"),
                new IndexOptions().name("idx_od_drafts_status_date")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("od_drafts").drop();
    }
}
