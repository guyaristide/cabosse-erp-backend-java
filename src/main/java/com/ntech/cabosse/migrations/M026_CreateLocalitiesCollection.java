package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 026 — référentiel des localités / villages ({@code localities}).
 *
 * <p>Remplace la saisie libre du champ {@code village} d'un membre. Index
 * unique sur {@code code}. <strong>Pas de seed</strong> : les villages sont
 * propres au tenant et se construisent à l'usage (ajout inline depuis la
 * fiche membre).</p>
 */
@ChangeUnit(id = "create_localities_collection", order = "026", author = "neiba")
public class M026_CreateLocalitiesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("localities").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_localities_code")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("localities").drop();
    }
}
