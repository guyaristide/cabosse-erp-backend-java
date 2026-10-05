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

/**
 * Migration 089 — le prévisionnel de décaissement, un par mois.
 *
 * <p>Le service cherche le mois avant de décider s'il insère ou s'il
 * remplace. Sans index unique, deux dépôts simultanés du même mois
 * passent tous deux par la branche insertion : le conseil approuverait
 * l'un et lirait l'autre.</p>
 *
 * <p>{@code runAlways} : une structure provisionnée avant cette
 * livraison doit recevoir l'index au démarrage suivant.
 * {@code MigrationIndexes.ensure} rend le passage idempotent et survit à
 * une forme d'index antérieure.</p>
 */
@ChangeUnit(id = "create_cash_forecasts_collection", order = "089", author = "neiba",
        runAlways = true)
public class M089_CreateCashForecastsCollection {

    private static final String COLLECTION = "cash_forecasts";
    static final String INDEX = "idx_cash_forecasts_month";

    @Execution
    public void execute(MongoDatabase database) {
        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(
                        Indexes.ascending("month"),
                        new IndexOptions().name(INDEX).unique(true))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).dropIndex(INDEX);
    }
}
