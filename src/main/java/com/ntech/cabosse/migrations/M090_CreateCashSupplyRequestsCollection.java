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
 * Migration 090 — les demandes d'approvisionnement de la caisse.
 *
 * <p>La référence est unique : deux demandes portant {@code DAC-2026-0007}
 * rendraient le journal d'audit illisible, et c'est par elle qu'on
 * remonte d'un transport de fonds à la décision qui l'a autorisé.</p>
 *
 * <p>L'index sur le statut sert la file d'approbation, qui ne lit que ce
 * qui attend.</p>
 *
 * <p>{@code runAlways} : une structure provisionnée avant cette
 * livraison doit recevoir les index au démarrage suivant.
 * {@code MigrationIndexes.ensure} rend le passage idempotent et survit à
 * une forme d'index antérieure.</p>
 */
@ChangeUnit(id = "create_cash_supply_requests_collection", order = "090", author = "neiba",
        runAlways = true)
public class M090_CreateCashSupplyRequestsCollection {

    private static final String COLLECTION = "cash_supply_requests";
    static final String REF_INDEX = "idx_cash_supply_ref";
    static final String STATUS_INDEX = "idx_cash_supply_status";

    @Execution
    public void execute(MongoDatabase database) {
        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(Indexes.ascending("ref"),
                        new IndexOptions().name(REF_INDEX).unique(true)),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.ascending("requestedOn")),
                        new IndexOptions().name(STATUS_INDEX))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).dropIndex(REF_INDEX);
        database.getCollection(COLLECTION).dropIndex(STATUS_INDEX);
    }
}
