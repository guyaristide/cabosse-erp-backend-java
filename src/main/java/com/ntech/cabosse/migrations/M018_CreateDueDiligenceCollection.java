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

/** Migration 018 — collection {@code due_diligence_statements}. Capacité HAS_EUDR_COMPLIANCE. */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_due_diligence_statements_collection", order = "018", author = "neiba", runAlways = true)
public class M018_CreateDueDiligenceCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_EUDR_COMPLIANCE)) {
            return;
        }
        database.getCollection("due_diligence_statements").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_ddr_ref")
                ),
                new IndexModel(
                        Indexes.ascending("saleId"),
                        new IndexOptions().unique(true).name("uniq_ddr_saleId")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_ddr_status")
                ),
                new IndexModel(
                        Indexes.descending("submittedAt"),
                        new IndexOptions().name("idx_ddr_submittedAt_desc").sparse(true)
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("due_diligence_statements").drop();
    }
}
