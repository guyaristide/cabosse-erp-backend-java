package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 051 — le reçu d'achat devient la seule voie d'apurement d'un
 * délégué, et son compte se suit comme un compte courant.
 *
 * <p>Jusqu'ici un reçu pointait sur une avance ({@code collectorAdvanceId}) ;
 * il porte désormais le délégué lui-même, parce que celui-ci reçoit des fonds
 * plusieurs fois dans la campagne et livre entre les versements. La reprise
 * remonte le délégué depuis l'avance déjà rattachée.</p>
 *
 * <p>Elle fixe aussi le montant payé sur le montant dû pour les reçus
 * antérieurs au paiement partiel : sans cela, un reliquat apparaîtrait
 * rétroactivement sur des achats intégralement réglés.</p>
 */
@ChangeUnit(id = "delegate_current_account", order = "051", author = "neiba")
public class M051_DelegateCurrentAccount {

    @Execution
    public void execute(MongoDatabase database) {
        MongoCollection<Document> purchases = database.getCollection("producer_purchases");

        Map<Object, Document> advancesById = new HashMap<>();
        for (Document a : database.getCollection("collector_advances").find()) {
            advancesById.put(a.get("_id"), a);
        }

        List<WriteModel<Document>> ops = new ArrayList<>();
        for (Document p : purchases.find()) {
            List<org.bson.conversions.Bson> updates = new ArrayList<>();

            if (p.get("amountPaid") == null && p.get("amount") != null) {
                updates.add(Updates.set("amountPaid", p.get("amount")));
            }
            if (p.get("delegateSupplierId") == null && p.get("collectorAdvanceId") != null) {
                Document advance = advancesById.get(p.get("collectorAdvanceId"));
                if (advance != null && advance.get("delegateSupplierId") != null) {
                    updates.add(Updates.set("delegateSupplierId", advance.get("delegateSupplierId")));
                    if (advance.getString("delegateName") != null) {
                        updates.add(Updates.set("delegateName", advance.getString("delegateName")));
                    }
                }
            }
            if (p.get("delegateMargin") == null) {
                // Aucune rémunération n'était constatée avant ce paramétrage.
                // Le zéro doit être un décimal : un montant entier est
                // illisible par le modèle, qui attend un Decimal128.
                updates.add(Updates.set("delegateMargin",
                        new org.bson.types.Decimal128(java.math.BigDecimal.ZERO)));
            }
            if (updates.isEmpty()) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", p.get("_id")), Updates.combine(updates)));
        }
        if (!ops.isEmpty()) purchases.bulkWrite(ops);

        // Le compte d'un délégué et ses bordereaux se consultent en permanence
        // pendant la campagne : deux index qui portent ces deux lectures.
        purchases.createIndex(Indexes.ascending("delegateSupplierId", "date"),
                new IndexOptions().name("idx_producer_purchase_delegate").background(true));
        purchases.createIndex(Indexes.ascending("deliveryRef"),
                new IndexOptions().name("idx_producer_purchase_delivery").background(true).sparse(true));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("producer_purchases").updateMany(
                Filters.exists("delegateSupplierId", true),
                Updates.combine(
                        Updates.unset("delegateSupplierId"),
                        Updates.unset("delegateName"),
                        Updates.unset("delegateMargin")));
    }
}
