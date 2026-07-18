package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 030 — sessions d'inventaire physique
 * ({@code inventory_sessions}).
 *
 * <p>Index unique sur {@code ref} (INV-YYYY-NNNN) et index composé
 * {@code (siteId, status)} pour la garde « une seule session non close
 * par site » et la liste filtrée.</p>
 */
@ChangeUnit(id = "create_inventory_sessions_collection", order = "030", author = "neiba")
public class M030_CreateInventorySessionsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("inventory_sessions").createIndex(
                Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_inventory_sessions_ref")
        );
        database.getCollection("inventory_sessions").createIndex(
                Indexes.ascending("siteId", "status"),
                new IndexOptions().name("idx_inventory_sessions_site_status")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("inventory_sessions").drop();
    }
}
