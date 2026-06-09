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

/** Migration 016 — collection {@code eudr_dossiers}. Capacité HAS_EUDR_COMPLIANCE. */
@ChangeUnit(id = "create_eudr_dossiers_collection", order = "016", author = "neiba")
public class M016_CreateEudrDossiersCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_EUDR_COMPLIANCE)) {
            return;
        }
        database.getCollection("eudr_dossiers").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("parcelId"),
                        new IndexOptions().unique(true).name("uniq_eudr_parcelId")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_eudr_status")
                ),
                new IndexModel(
                        Indexes.ascending("complianceExpiresOn"),
                        new IndexOptions().name("idx_eudr_expires").sparse(true)
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("eudr_dossiers").drop();
    }
}
