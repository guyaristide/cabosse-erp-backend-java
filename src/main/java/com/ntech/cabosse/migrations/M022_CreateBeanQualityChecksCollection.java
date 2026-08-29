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

/** Migration 022 — collection {@code bean_quality_checks}. Capacité HAS_DRYING. */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_bean_quality_checks_collection", order = "022", author = "neiba", runAlways = true)
public class M022_CreateBeanQualityChecksCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_DRYING)) {
            return;
        }
        database.getCollection("bean_quality_checks").createIndexes(List.of(
                new IndexModel(Indexes.ascending("ref"), new IndexOptions().unique(true).name("uniq_qc_ref")),
                // Partiel, et non simplement unique : un contrôle qualité peut
                // vivre sans lot de séchage (M074), et un index unique sur un
                // champ absent range tous ces documents sous une même clé
                // nulle. La forme doit être celle du schéma d'aujourd'hui,
                // faute de quoi le rejeu de cette migration à l'activation
                // d'un tenant entre en conflit avec l'index en place et
                // bloque le démarrage.
                new IndexModel(Indexes.ascending("dryingBatchId"),
                        new IndexOptions().unique(true).name("uniq_qc_dryingBatchId")
                                .partialFilterExpression(
                                        new org.bson.Document("dryingBatchId",
                                                new org.bson.Document("$type", "binData")))),
                new IndexModel(Indexes.ascending("conformOverall"), new IndexOptions().name("idx_qc_conform")),
                new IndexModel(Indexes.ascending("lotRef"), new IndexOptions().name("idx_qc_lotRef").sparse(true))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("bean_quality_checks").drop();
    }
}
