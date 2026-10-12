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
 * Migration 092 — les reçus posés sur un fournisseur apporteur.
 *
 * <p>Depuis le 12/10/2026, un reçu dont le document ne nomme pas le
 * producteur repose sur le fournisseur qui a apporté la matière. Ce que
 * la coopérative lui doit se lit par ce champ, à chaque ouverture de la
 * file à payer et de l'échéancier : sans index, la lecture parcourt
 * toute la collection des reçus, qui compte une ligne par livraison de
 * la campagne.</p>
 *
 * <p>Index partiel plutôt que creux : la très grande majorité des reçus
 * nomment leur producteur et n'ont rien à indexer ici.</p>
 *
 * <p>{@code runAlways} : une structure provisionnée avant cette
 * livraison doit recevoir l'index au démarrage suivant.</p>
 */
@ChangeUnit(id = "index_producer_purchase_by_supplier", order = "092", author = "neiba",
        runAlways = true)
public class M092_IndexProducerPurchaseBySupplier {

    private static final String COLLECTION = "producer_purchases";
    static final String SUPPLIER_INDEX = "idx_producer_purchase_supplier";

    @Execution
    public void execute(MongoDatabase database) {
        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(Indexes.ascending("supplierId"),
                        new IndexOptions().name(SUPPLIER_INDEX)
                                .partialFilterExpression(
                                        new org.bson.Document("supplierId",
                                                new org.bson.Document("$exists", true))))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).dropIndex(SUPPLIER_INDEX);
    }
}
