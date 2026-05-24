package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 009 — collection {@code manufacturing_orders} (M3 Production)
 * + ajout d'un index sparse sur {@code stock_movements.lotRef} (M5
 * étendu pour les besoins production).
 *
 * <p>Compteurs OF et lot vivent dans la collection {@code counters}
 * partagée (clés {@code manufacturing_order:YYYY} et {@code lot:YYYY}) ;
 * pas besoin de créer la collection ici, le {@code findOneAndUpdate}
 * avec {@code upsert=true} la matérialise.</p>
 */
@ChangeUnit(id = "create_production_collection", order = "009", author = "neiba")
public class M009_CreateProductionCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("manufacturing_orders").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_manufacturing_orders_ref")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.descending("scheduledDate")
                        ),
                        new IndexOptions().name("idx_manufacturing_orders_status_scheduledDate")
                ),
                new IndexModel(
                        Indexes.ascending("siteId"),
                        new IndexOptions().name("idx_manufacturing_orders_siteId")
                ),
                new IndexModel(
                        Indexes.ascending("recipeId"),
                        new IndexOptions().name("idx_manufacturing_orders_recipeId")
                ),
                new IndexModel(
                        Indexes.ascending("finishedProductId"),
                        new IndexOptions().name("idx_manufacturing_orders_finishedProductId")
                ),
                new IndexModel(
                        Indexes.ascending("lotRef"),
                        new IndexOptions()
                                .name("idx_manufacturing_orders_lotRef")
                                .sparse(true)
                )
        ));

        // Index sparse sur lotRef côté mouvements de stock (M5 enrichi
        // pour la traçabilité lot M3).
        database.getCollection("stock_movements").createIndex(
                Indexes.ascending("lotRef"),
                new IndexOptions()
                        .name("idx_stock_movements_lotRef")
                        .sparse(true)
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("manufacturing_orders").dropIndexes();
        try {
            database.getCollection("stock_movements").dropIndex("idx_stock_movements_lotRef");
        } catch (Exception ignored) { /* l'index peut ne pas exister sur un rollback partiel */ }
    }
}
