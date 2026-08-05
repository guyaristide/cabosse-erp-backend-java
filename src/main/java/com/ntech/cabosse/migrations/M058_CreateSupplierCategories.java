package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 058 — catégories de fournisseur.
 *
 * <p>Aucune catégorie n'est semée : celles d'une coopérative de cacao
 * (délégué collecteur, planteur individuel) n'ont rien à voir avec celles
 * d'une unité qui achète du latex ou du manioc. Le tenant crée les
 * siennes.</p>
 */
@ChangeUnit(id = "create_supplier_categories", order = "058", author = "neiba")
public class M058_CreateSupplierCategories {

    @Execution
    public void execute(MongoDatabase database) {
        var categories = database.getCollection("supplier_categories");
        categories.createIndex(Indexes.ascending("code"),
                new IndexOptions().name("uniq_supplier_categories_code").unique(true).background(true));

        // Retrouver les fournisseurs d'une catégorie sans balayer la
        // collection, notamment pour l'état de fin de campagne.
        database.getCollection("suppliers").createIndex(Indexes.ascending("categoryId"),
                new IndexOptions().name("idx_suppliers_category").background(true));
        database.getCollection("producer_purchases").createIndex(
                Indexes.ascending("supplierCategoryId"),
                new IndexOptions().name("idx_producer_purchases_category").background(true));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("supplier_categories").drop();
    }
}
