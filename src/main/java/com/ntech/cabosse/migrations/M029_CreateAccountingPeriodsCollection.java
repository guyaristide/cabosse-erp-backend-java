package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 029 — périodes comptables verrouillées
 * ({@code accounting_periods}).
 *
 * <p>Un document n'existe que pour une période déjà clôturée (ou rouverte
 * après clôture). Index unique sur {@code period} (format {@code YYYY-MM})
 * : une période ne peut être verrouillée qu'une fois, la réouverture
 * modifie le document existant.</p>
 */
@ChangeUnit(id = "create_accounting_periods_collection", order = "029", author = "neiba")
public class M029_CreateAccountingPeriodsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("accounting_periods").createIndex(
                Indexes.ascending("period"),
                new IndexOptions().unique(true).name("uniq_accounting_periods_period")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("accounting_periods").drop();
    }
}
