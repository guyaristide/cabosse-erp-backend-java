package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 054 — crédits et avances aux producteurs membres.
 *
 * <p>La coopérative avance des fonds à ses membres pour des besoins qui
 * n'ont rien à voir avec la collecte, et se rembourse par retenue sur
 * leurs livraisons. Ces engagements vivaient sur tableur, ce qui rendait le
 * point à une date donnée impossible à tenir.</p>
 *
 * <p>Deux index : le reste dû d'un producteur, consulté à chaque fois qu'on
 * s'apprête à le payer, et la référence affichable.</p>
 */
@ChangeUnit(id = "create_member_credits_collection", order = "054", author = "neiba")
public class M054_CreateMemberCreditsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
            return;
        }
        var credits = database.getCollection("member_credits");
        credits.createIndex(Indexes.ascending("memberId", "status"),
                new IndexOptions().name("idx_member_credits_member_status").background(true));
        credits.createIndex(Indexes.ascending("ref"),
                new IndexOptions().name("uniq_member_credits_ref").unique(true).background(true));
        credits.createIndex(Indexes.ascending("campaignId"),
                new IndexOptions().name("idx_member_credits_campaign").sparse(true).background(true));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("member_credits").drop();
    }
}
