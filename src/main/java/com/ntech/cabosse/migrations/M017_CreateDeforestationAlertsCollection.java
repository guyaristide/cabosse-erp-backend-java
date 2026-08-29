package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/** Migration 017 — collection {@code deforestation_alerts}. Capacité HAS_EUDR_COMPLIANCE. */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_deforestation_alerts_collection", order = "017", author = "neiba", runAlways = true)
public class M017_CreateDeforestationAlertsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_EUDR_COMPLIANCE)) {
            return;
        }
        MigrationIndexes.ensure(database.getCollection("deforestation_alerts"), List.of(
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
