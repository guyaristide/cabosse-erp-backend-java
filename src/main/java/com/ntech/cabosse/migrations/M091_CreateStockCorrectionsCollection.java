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
 * Migration 091 — les corrections de stock du magasin.
 *
 * <p>La référence est unique : c'est par elle qu'on remonte d'une sortie
 * de stock au constat qui l'explique, et d'une pièce comptable à la
 * perte qu'elle enregistre.</p>
 *
 * <p>L'index par jour sert la fiche du jour, qui lit les corrections
 * d'une date, d'un site et d'un article à chaque ouverture.</p>
 *
 * <p>{@code runAlways} : une structure provisionnée avant cette
 * livraison doit recevoir les index au démarrage suivant.
 * {@code MigrationIndexes.ensure} rend le passage idempotent et survit à
 * une forme d'index antérieure.</p>
 */
@ChangeUnit(id = "create_stock_corrections_collection", order = "091", author = "neiba",
        runAlways = true)
public class M091_CreateStockCorrectionsCollection {

    private static final String COLLECTION = "stock_corrections";
    static final String REF_INDEX = "idx_stock_correction_ref";
    static final String DAY_INDEX = "idx_stock_correction_day";

    @Execution
    public void execute(MongoDatabase database) {
        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(Indexes.ascending("ref"),
                        new IndexOptions().name(REF_INDEX).unique(true)),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("date"),
                                Indexes.ascending("siteId"),
                                Indexes.ascending("articleId")),
                        new IndexOptions().name(DAY_INDEX))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).dropIndex(REF_INDEX);
        database.getCollection(COLLECTION).dropIndex(DAY_INDEX);
    }
}
