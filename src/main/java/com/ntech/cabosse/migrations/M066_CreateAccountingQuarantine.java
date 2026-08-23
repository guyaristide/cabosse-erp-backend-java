package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 066 — écritures retenues faute de période ouverte.
 *
 * <p>Collection volontairement distincte du journal : tant qu'une écriture
 * n'est pas régularisée, elle ne doit peser sur aucun état. Une pièce
 * simplement marquée dans le journal finirait par être comptée par un
 * export qui aurait oublié de la filtrer.</p>
 */
@ChangeUnit(id = "create_accounting_quarantine", order = "066", author = "neiba")
public class M066_CreateAccountingQuarantine {

    static final String COLLECTION = "accounting_quarantine";

    @Execution
    public void execute(MongoDatabase database) {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) { exists = true; break; }
        }
        if (!exists) database.createCollection(COLLECTION);

        database.getCollection(COLLECTION).createIndexes(List.of(
                // La liste du comptable : les lignes en attente, par date.
                new com.mongodb.client.model.IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.ascending("date")),
                        new IndexOptions().name("idx_quarantine_status_date")),
                // « Cette opération a-t-elle déjà une demande en attente ? »
                // posée à chaque rejeu, donc à chaque synchronisation.
                new com.mongodb.client.model.IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("sourceType"),
                                Indexes.ascending("sourceId")),
                        new IndexOptions().name("idx_quarantine_source"))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).drop();
    }
}
