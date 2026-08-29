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

/** Migration 021 — collection {@code drying_batches}. Capacité HAS_DRYING. */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_drying_batches_collection", order = "021", author = "neiba", runAlways = true)
public class M021_CreateDryingBatchesCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_DRYING)) {
            return;
        }
        MigrationIndexes.ensure(database.getCollection("drying_batches"), List.of(
                new IndexModel(Indexes.ascending("ref"), new IndexOptions().unique(true).name("uniq_drying_ref")),
                new IndexModel(Indexes.ascending("status"), new IndexOptions().name("idx_drying_status")),
                new IndexModel(Indexes.descending("startedAt"), new IndexOptions().name("idx_drying_startedAt_desc").sparse(true)),
                new IndexModel(Indexes.ascending("fermentationBatchIds"), new IndexOptions().name("idx_drying_fermentationBatchIds"))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("drying_batches").drop();
    }
}
