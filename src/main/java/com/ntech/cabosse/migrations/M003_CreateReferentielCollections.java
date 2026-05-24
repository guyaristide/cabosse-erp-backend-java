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
 * Migration 003 — collections référentiels tenant (articles, suppliers,
 * customers, expense_types, recipes) avec leurs indexes.
 *
 * <p>Conventions :
 * <ul>
 *   <li>{@code code} unique sur tous les référentiels — c'est la FK
 *       stable depuis les documents métier ({@code stockMovement.articleCode},
 *       {@code purchaseOrder.supplierCode}, etc.).</li>
 *   <li>{@code type + active} pour les sélecteurs UI (articles).</li>
 *   <li>{@code active} seul pour les autres (filtre rapide dropdowns).</li>
 *   <li>Recipes : index sur {@code finishedProductId} pour retrouver la
 *       recette d'un PF rapidement (OF planning).</li>
 * </ul>
 */
@ChangeUnit(id = "create_referentiel_collections", order = "003", author = "neiba")
public class M003_CreateReferentielCollections {

    @Execution
    public void execute(MongoDatabase database) {
        // ── Articles ──
        database.getCollection("articles").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_articles_code")
                ),
                new IndexModel(
                        Indexes.compoundIndex(Indexes.ascending("type"), Indexes.ascending("active")),
                        new IndexOptions().name("idx_articles_type_active")
                ),
                new IndexModel(
                        Indexes.ascending("activityCode"),
                        new IndexOptions().name("idx_articles_activityCode")
                )
        ));

        // ── Suppliers ──
        database.getCollection("suppliers").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_suppliers_code")
                ),
                new IndexModel(
                        Indexes.ascending("active"),
                        new IndexOptions().name("idx_suppliers_active")
                )
        ));

        // ── Customers ──
        database.getCollection("customers").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_customers_code")
                ),
                new IndexModel(
                        Indexes.compoundIndex(Indexes.ascending("type"), Indexes.ascending("active")),
                        new IndexOptions().name("idx_customers_type_active")
                )
        ));

        // ── Expense types ──
        database.getCollection("expense_types").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_expense_types_code")
                ),
                new IndexModel(
                        Indexes.ascending("active"),
                        new IndexOptions().name("idx_expense_types_active")
                )
        ));

        // ── Recipes ──
        database.getCollection("recipes").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_recipes_code")
                ),
                new IndexModel(
                        Indexes.ascending("finishedProductId"),
                        new IndexOptions().name("idx_recipes_finishedProductId")
                ),
                new IndexModel(
                        Indexes.ascending("active"),
                        new IndexOptions().name("idx_recipes_active")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("articles").dropIndexes();
        database.getCollection("suppliers").dropIndexes();
        database.getCollection("customers").dropIndexes();
        database.getCollection("expense_types").dropIndexes();
        database.getCollection("recipes").dropIndexes();
    }
}
