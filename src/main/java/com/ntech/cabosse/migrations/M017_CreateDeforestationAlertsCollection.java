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

/** Migration 017 — collection {@code deforestation_alerts}. Capacité HAS_EUDR_COMPLIANCE. */
@ChangeUnit(id = "create_deforestation_alerts_collection", order = "017", author = "neiba")
public class M017_CreateDeforestationAlertsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_EUDR_COMPLIANCE)) {
            return;
        }
        database.getCollection("deforestation_alerts").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("parcelId"),
                        new IndexOptions().name("idx_deforestation_parcelId")
                ),
                new IndexModel(
                        Indexes.descending("detectedAt"),
                        new IndexOptions().name("idx_deforestation_detectedAt_desc")
                ),
                new IndexModel(
                        Indexes.ascending("severity"),
                        new IndexOptions().name("idx_deforestation_severity")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_deforestation_status")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("deforestation_alerts").drop();
    }
}
