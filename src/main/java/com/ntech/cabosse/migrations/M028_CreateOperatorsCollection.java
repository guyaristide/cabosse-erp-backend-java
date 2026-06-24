package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 028 — référentiel léger des opérateurs ({@code operators}).
 *
 * <p>Remplace la saisie libre des champs « opérateur » des opérations
 * terrain (brassage de fermentation, etc.). Index unique sur {@code code}.
 * Pas de seed : la liste se construit à l'usage (ajout inline).</p>
 */
@ChangeUnit(id = "create_operators_collection", order = "028", author = "neiba")
public class M028_CreateOperatorsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("operators").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_operators_code")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("operators").drop();
    }
}
