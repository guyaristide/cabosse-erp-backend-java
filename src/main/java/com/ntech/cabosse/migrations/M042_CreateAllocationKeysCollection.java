package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 042 — clés de répartition analytique ({@code allocation_keys},
 * backlog CPT-17 : ventilation des charges indirectes sur plusieurs centres
 * de coût au prorata de poids éditables).
 */
@ChangeUnit(id = "create_allocation_keys_collection", order = "042", author = "neiba")
public class M042_CreateAllocationKeysCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("allocation_keys").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_allocation_keys_code"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("allocation_keys").drop();
    }
}
