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
 * Migration 006 — collection {@code purchase_orders} (M2 Achats) et son
 * compteur de séquence dans la base d'un tenant.
 *
 * <p>Indexes posés sur {@code purchase_orders} :
 * <ul>
 *   <li>{@code ref} unique — un BC ne peut pas dupliquer une référence.</li>
 *   <li>{@code status + createdAt} — listes filtrées par statut, tri par date.</li>
 *   <li>{@code supplierId} — recherche des BC d'un fournisseur.</li>
 *   <li>{@code siteId} — recherche des BC sur un site.</li>
 * </ul>
 *
 * <p>La collection {@code counters} sert au compteur séquentiel des refs
 * BC (cf. {@code PurchaseOrderRefService}) — pas d'index dédié, le
 * {@code _id} est la clé naturelle.</p>
 */
@ChangeUnit(id = "create_purchase_orders_collection", order = "006", author = "neiba")
public class M006_CreatePurchaseOrdersCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("purchase_orders").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_purchase_orders_ref")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.descending("createdAt")
                        ),
                        new IndexOptions().name("idx_purchase_orders_status_createdAt")
                ),
                new IndexModel(
                        Indexes.ascending("supplierId"),
                        new IndexOptions().name("idx_purchase_orders_supplierId")
                ),
                new IndexModel(
                        Indexes.ascending("siteId"),
                        new IndexOptions().name("idx_purchase_orders_siteId")
                )
        ));
        // counters : pas de createCollection explicite, Mongo crée à la
        // première insertion. Pas d'index nécessaire ({@code _id} suffit).
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("purchase_orders").dropIndexes();
    }
}
