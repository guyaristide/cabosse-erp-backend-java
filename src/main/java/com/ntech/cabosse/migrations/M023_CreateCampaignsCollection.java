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
 * Migration 023 — collection {@code campaigns} pour les structures avec
 * capacité {@link TenantCapability#HAS_MEMBERS}. No-op silencieux pour
 * les autres tenants (entreprises privées sans rémunération de membres).
 */
@ChangeUnit(id = "create_campaigns_collection", order = "023", author = "neiba")
public class M023_CreateCampaignsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
            return;
        }
        database.getCollection("campaigns").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_campaigns_code")
                ),
                new IndexModel(
                        Indexes.ascending("campaignYear"),
                        new IndexOptions().name("idx_campaigns_year")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_campaigns_status")
                ),
                // Garde-fou DB : au plus une campagne OPEN à la fois (en
                // complément de la validation applicative).
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions()
                                .name("uniq_campaigns_open")
                                .unique(true)
                                .partialFilterExpression(
                                        new org.bson.Document("status", "OPEN")
                                )
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("campaigns").drop();
    }
}
