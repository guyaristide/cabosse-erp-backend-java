package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Migration 095 — traces de requêtes dans la base de la structure (SEC-16).
 *
 * <p>L'index sur {@code occurredAt} est l'index TTL lui-même : MongoDB
 * refuse deux index sur la même clé, et c'est de toute façon lui qui sert
 * les lectures par intervalle de l'archive des journaux. Il est posé avec
 * la rétention par défaut, deux ans ; une unité Mongock est statique et ne
 * lit pas la configuration. C'est {@code RequestTraceRetention} qui aligne
 * ensuite la durée sur {@code cabosse.trace.retention-days}, au démarrage
 * et par {@code collMod}, sans reconstruire l'index.</p>
 *
 * <p>Pas de rejeu : une seconde passe trouverait l'index avec une durée
 * modifiée par la configuration et la ramènerait au défaut.</p>
 */
@ChangeUnit(id = "create_request_traces", order = "095", author = "neiba")
public class M095_CreateRequestTraces {

    static final String COLLECTION = "request_traces";
    static final String TTL_INDEX = "ttl_request_traces_occurredAt";
    static final String ACTOR_INDEX = "idx_request_traces_actor_occurredAt";

    /** Deux ans, la valeur par défaut de {@code cabosse.trace.retention-days}. */
    static final long DEFAULT_RETENTION_SECONDS = 730L * 24 * 3600;

    @Execution
    public void execute(MongoDatabase database) {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) { exists = true; break; }
        }
        if (!exists) database.createCollection(COLLECTION);

        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(Indexes.ascending("occurredAt"),
                        new IndexOptions().name(TTL_INDEX)
                                .expireAfter(DEFAULT_RETENTION_SECONDS, TimeUnit.SECONDS)),
                new IndexModel(Indexes.compoundIndex(
                                Indexes.ascending("actorEmail"),
                                Indexes.descending("occurredAt")),
                        new IndexOptions().name(ACTOR_INDEX))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).drop();
    }
}
