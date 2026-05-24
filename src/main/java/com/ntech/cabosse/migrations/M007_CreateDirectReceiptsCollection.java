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
 * Migration 007 — collection {@code direct_receipts} (Réception directe,
 * achats hors BC) et indexes utiles dans la base d'un tenant.
 *
 * <p>Indexes :</p>
 * <ul>
 *   <li>{@code ref} unique — chaque RD a un identifiant.</li>
 *   <li>{@code status + receivedDate} — listes filtrées par statut de
 *       paiement, tri chronologique.</li>
 *   <li>{@code siteId} — recherche des réceptions d'un site.</li>
 *   <li>{@code articleId} — historique d'achats d'un article.</li>
 * </ul>
 */
@ChangeUnit(id = "create_direct_receipts_collection", order = "007", author = "neiba")
public class M007_CreateDirectReceiptsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("direct_receipts").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_direct_receipts_ref")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.descending("receivedDate")
                        ),
                        new IndexOptions().name("idx_direct_receipts_status_receivedDate")
                ),
                new IndexModel(
                        Indexes.ascending("siteId"),
                        new IndexOptions().name("idx_direct_receipts_siteId")
                ),
                new IndexModel(
                        Indexes.ascending("articleId"),
                        new IndexOptions().name("idx_direct_receipts_articleId")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("direct_receipts").dropIndexes();
    }
}
