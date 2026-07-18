package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 035 — exercices comptables arrêtés ({@code fiscal_years},
 * backlog CPT-12). Un document n'existe que pour un exercice arrêté ;
 * index unique sur {@code endDate} : un exercice ne peut être arrêté
 * qu'une fois, l'affectation modifie le document existant.
 */
@ChangeUnit(id = "create_fiscal_years_collection", order = "035", author = "neiba")
public class M035_CreateFiscalYearsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("fiscal_years").createIndex(
                Indexes.ascending("endDate"),
                new IndexOptions().unique(true).name("uniq_fiscal_years_end")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("fiscal_years").drop();
    }
}
