package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/** Migration 020 — collection {@code fermentation_batches}. Capacité HAS_FERMENTATION. */
@ChangeUnit(id = "create_fermentation_batches_collection", order = "020", author = "neiba")
public class M020_CreateFermentationBatchesCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_FERMENTATION)) {
            return;
        }
        database.getCollection("fermentation_batches").createIndexes(List.of(
                new IndexModel(Indexes.ascending("ref"), new IndexOptions().unique(true).name("uniq_fermentation_ref")),
                new IndexModel(Indexes.ascending("status"), new IndexOptions().name("idx_fermentation_status")),
                new IndexModel(Indexes.descending("startedAt"), new IndexOptions().name("idx_fermentation_startedAt_desc").sparse(true)),
                new IndexModel(Indexes.ascending("harvestIds"), new IndexOptions().name("idx_fermentation_harvestIds"))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("fermentation_batches").drop();
    }
}
