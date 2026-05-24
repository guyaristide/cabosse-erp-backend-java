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
 * Migration 002 — création de la collection {@code sites} et de ses
 * indexes dans la base d'un tenant.
 *
 * <p>Indexes posés :
 * <ul>
 *   <li>{@code code} unique — clé FK depuis le stock, les achats, etc.</li>
 *   <li>{@code type + active} pour les sélecteurs UI filtrés par type.</li>
 * </ul>
 */
@ChangeUnit(id = "create_sites_collection", order = "002", author = "neiba")
public class M002_CreateSitesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("sites").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_sites_code")
                ),
                new IndexModel(
                        Indexes.compoundIndex(Indexes.ascending("type"), Indexes.ascending("active")),
                        new IndexOptions().name("idx_sites_type_active")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("sites").dropIndexes();
    }
}
