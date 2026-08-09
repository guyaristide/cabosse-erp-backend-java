package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 056 — règlements aux fournisseurs de matière première.
 *
 * <p>Les livraisons se paient en plusieurs fois. La collection porte les
 * versements et les livraisons qu'ils soldent ; le cumul payé, lui, reste
 * sur la livraison, là où on le lit.</p>
 */
@ChangeUnit(id = "create_producer_payments", order = "056", author = "neiba")
public class M056_CreateProducerPayments {

    /**
     * Zéro décimal. Un {@code 0} entier produirait un montant que le
     * modèle ne sait pas relire : le repli d'un {@code $ifNull} doit
     * porter le même type que la valeur qu'il remplace.
     */
    private static final org.bson.types.Decimal128 ZERO =
            new org.bson.types.Decimal128(java.math.BigDecimal.ZERO);

    @Execution
    public void execute(MongoDatabase database) {
        var payments = database.getCollection("producer_payments");
        payments.createIndex(Indexes.ascending("ref"),
                new IndexOptions().name("uniq_producer_payments_ref").unique(true).background(true));
        payments.createIndex(Indexes.descending("date"),
                new IndexOptions().name("idx_producer_payments_date").background(true));
        payments.createIndex(Indexes.ascending("memberId", "date"),
                new IndexOptions().name("idx_producer_payments_member").background(true));
        payments.createIndex(Indexes.ascending("delegateSupplierId", "date"),
                new IndexOptions().name("idx_producer_payments_delegate").background(true));
        // Retrouver l'historique de versements d'une livraison donnée.
        payments.createIndex(Indexes.ascending("allocations.purchaseId"),
                new IndexOptions().name("idx_producer_payments_allocation").background(true));

        // Les reçus antérieurs au paiement fractionné n'ont pas de cumul
        // payé. L'affichage les lisait comme soldés ; la requête de reste
        // à payer, elle, lirait l'absence comme zéro et les ferait
        // ressurgir comme dus. On fige donc la lecture d'origine : payé =
        // montant dû, retenues déduites.
        database.getCollection("producer_purchases").updateMany(
                com.mongodb.client.model.Filters.exists("amountPaidFcfa", false),
                java.util.List.of(new org.bson.Document("$set", new org.bson.Document(
                        "amountPaidFcfa", new org.bson.Document("$subtract", java.util.List.of(
                                new org.bson.Document("$ifNull",
                                        java.util.List.of("$amountFcfa", ZERO)),
                                new org.bson.Document("$ifNull",
                                        java.util.List.of("$creditImputedFcfa", ZERO))))))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("producer_payments").drop();
    }
}
