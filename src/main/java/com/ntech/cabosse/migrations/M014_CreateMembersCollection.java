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

/**
 * Migration 014 — collection {@code members} pour les structures avec
 * capacité {@link TenantCapability#HAS_MEMBERS} (COOPERATIVE,
 * INFORMAL_GROUP). No-op silencieux pour les autres tenants.
 */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_members_collection", order = "014", author = "neiba", runAlways = true)
public class M014_CreateMembersCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
            return;
        }
        database.getCollection("members").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_members_code")
                ),
                new IndexModel(
                        Indexes.ascending("name"),
                        new IndexOptions().name("idx_members_name")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_members_status")
                ),
                new IndexModel(
                        Indexes.ascending("supplierId"),
                        new IndexOptions().name("idx_members_supplierId").sparse(true)
                ),
                new IndexModel(
                        Indexes.ascending("village"),
                        new IndexOptions().name("idx_members_village").sparse(true)
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("members").drop();
    }
}
