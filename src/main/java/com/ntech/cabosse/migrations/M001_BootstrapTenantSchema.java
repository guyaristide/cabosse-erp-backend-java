package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 001 — bootstrap d'une base tenant fraîchement provisionnée.
 *
 * <p>À ce stade Phase A, on ne crée encore aucune entité métier ; ce
 * change unit ne fait qu'amorcer la chaîne Mongock en posant un index
 * sur une collection technique {@code _provisioning_marker} (utilisée
 * par {@code TenantProvisioningService} en Phase B pour forcer la
 * création physique de la base).</p>
 *
 * <p>Les migrations suivantes (Phase B+) créeront les vraies collections
 * et leurs indexes.</p>
 */
@ChangeUnit(id = "bootstrap_tenant_schema", order = "001", author = "neiba")
public class M001_BootstrapTenantSchema {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("_provisioning_marker").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("createdAt"),
                        new IndexOptions().name("idx_provisioning_marker_createdAt")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("_provisioning_marker").dropIndexes();
    }
}
