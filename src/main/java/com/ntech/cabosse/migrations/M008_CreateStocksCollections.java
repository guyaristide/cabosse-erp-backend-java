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
 * Migration 008 — collections {@code stock_items} et {@code stock_movements}
 * dans la base de chaque tenant. Le compteur {@code stock_movement:YYYY}
 * vit dans la collection {@code counters} partagée avec les autres
 * compteurs (RD, BC) — pas besoin de créer la collection ici, le
 * {@code findOneAndUpdate} avec {@code upsert=true} la matérialise.
 *
 * <p>Indexes posés pour soutenir les requêtes les plus fréquentes :</p>
 * <ul>
 *   <li><strong>stock_items</strong> :
 *     <ul>
 *       <li>{@code (articleId, siteId)} unique — identité métier d'un stock</li>
 *       <li>{@code (siteId, articleName)} — liste par site triée alpha</li>
 *       <li>{@code (siteId, articleType)} — filtres tabs Matières/Emballages/…</li>
 *     </ul>
 *   </li>
 *   <li><strong>stock_movements</strong> :
 *     <ul>
 *       <li>{@code ref} unique</li>
 *       <li>{@code (articleId, siteId, occurredAt:-1)} — historique fiche stock</li>
 *       <li>{@code (siteId, occurredAt:-1)} — journal d'un site</li>
 *       <li>{@code sourceEntityId} — retrouver les mvts d'une RD/BC/OF/Vente</li>
 *       <li>{@code transferId} — paire OUT/IN d'un transfert</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@ChangeUnit(id = "create_stocks_collections", order = "008", author = "neiba")
public class M008_CreateStocksCollections {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("stock_items").createIndexes(List.of(
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("articleId"),
                                Indexes.ascending("siteId")
                        ),
                        new IndexOptions().unique(true).name("uniq_stock_items_article_site")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("siteId"),
                                Indexes.ascending("articleName")
                        ),
                        new IndexOptions().name("idx_stock_items_site_name")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("siteId"),
                                Indexes.ascending("articleType")
                        ),
                        new IndexOptions().name("idx_stock_items_site_type")
                )
        ));

        database.getCollection("stock_movements").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_stock_movements_ref")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("articleId"),
                                Indexes.ascending("siteId"),
                                Indexes.descending("occurredAt")
                        ),
                        new IndexOptions().name("idx_stock_movements_article_site_date")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("siteId"),
                                Indexes.descending("occurredAt")
                        ),
                        new IndexOptions().name("idx_stock_movements_site_date")
                ),
                new IndexModel(
                        Indexes.ascending("sourceEntityId"),
                        new IndexOptions()
                                .name("idx_stock_movements_sourceEntityId")
                                .sparse(true)
                ),
                new IndexModel(
                        Indexes.ascending("transferId"),
                        new IndexOptions()
                                .name("idx_stock_movements_transferId")
                                .sparse(true)
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("stock_items").dropIndexes();
        database.getCollection("stock_movements").dropIndexes();
    }
}
