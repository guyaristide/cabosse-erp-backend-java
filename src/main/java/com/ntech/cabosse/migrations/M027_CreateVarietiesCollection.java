package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 027 — référentiel des variétés cultivées ({@code varieties}).
 *
 * <p>Remplace la saisie libre du champ {@code variety} d'une parcelle. Index
 * unique sur {@code code}. Pas de seed : les variétés dépendent de la
 * filière du tenant et se construisent à l'usage.</p>
 */
@ChangeUnit(id = "create_varieties_collection", order = "027", author = "neiba")
public class M027_CreateVarietiesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("varieties").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_varieties_code")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("varieties").drop();
    }
}
