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

/** Migration 019 — collection {@code harvests}. Capacité HAS_PARCELS. */
@ChangeUnit(id = "create_harvests_collection", order = "019", author = "neiba")
public class M019_CreateHarvestsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_PARCELS)) {
            return;
        }
        database.getCollection("harvests").createIndexes(List.of(
                new IndexModel(Indexes.ascending("code"), new IndexOptions().unique(true).name("uniq_harvests_code")),
                new IndexModel(Indexes.ascending("parcelId"), new IndexOptions().name("idx_harvests_parcelId")),
                new IndexModel(Indexes.ascending("memberId"), new IndexOptions().name("idx_harvests_memberId").sparse(true)),
                new IndexModel(Indexes.ascending("campaignYear"), new IndexOptions().name("idx_harvests_campaign")),
                new IndexModel(Indexes.descending("harvestDate"), new IndexOptions().name("idx_harvests_date_desc"))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("harvests").drop();
    }
}
