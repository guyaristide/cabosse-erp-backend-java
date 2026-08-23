package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.concurrent.TimeUnit;

/**
 * Migration 065 — traces d'idempotence dans la base du tenant.
 *
 * <p>La clé est l'identifiant du document : c'est l'unicité de la clé
 * primaire qui arbitre entre deux requêtes concurrentes, sans index
 * supplémentaire à poser ni lecture préalable à faire.</p>
 *
 * <p>Un index TTL sur {@code expiresAt} laisse MongoDB purger les traces
 * périmées. Sans lui, la collection grossirait indéfiniment pour des
 * données dont l'intérêt ne dépasse pas quelques semaines.</p>
 */
@ChangeUnit(id = "create_idempotency_keys", order = "065", author = "neiba")
public class M065_CreateIdempotencyKeys {

    static final String COLLECTION = "idempotency_keys";

    @Execution
    public void execute(MongoDatabase database) {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) { exists = true; break; }
        }
        if (!exists) database.createCollection(COLLECTION);

        database.getCollection(COLLECTION).createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions()
                        .name("ttl_idempotency_expiresAt")
                        .expireAfter(0L, TimeUnit.SECONDS));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).drop();
    }
}
