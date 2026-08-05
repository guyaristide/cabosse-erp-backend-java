package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 055 — transports de fonds et points de caisse.
 *
 * <p>Sans agence bancaire sur place, l'argent de la coopérative voyage en
 * espèces. Ces deux collections tracent ce qui part, ce qui arrive, et ce
 * que la caisse contient réellement à une date.</p>
 */
@ChangeUnit(id = "create_treasury_collections", order = "055", author = "neiba")
public class M055_CreateTreasuryCollections {

    /**
     * Comptes que le transport de fonds met en jeu et que le plan semé ne
     * portait pas : le compte de passage, sans lequel une somme partie et
     * non encore reçue disparaîtrait des livres, et le compte où se
     * constate un manquant.
     */
    private static final String[][] ACCOUNTS = {
            {"585000", "Virements de fonds", "TRESORERIE"},
            {"658800", "Charges diverses : écarts de trésorerie", "CHARGES"},
    };

    @Execution
    public void execute(MongoDatabase database) {
        var chart = database.getCollection("chart_of_accounts");
        java.time.Instant now = java.time.Instant.now();
        for (String[] row : ACCOUNTS) {
            if (chart.find(com.mongodb.client.model.Filters.eq("number", row[0])).first() != null) {
                continue;
            }
            chart.insertOne(new org.bson.Document("_id", java.util.UUID.randomUUID())
                    .append("number", row[0])
                    .append("label", row[1])
                    .append("family", row[2])
                    .append("active", true)
                    .append("createdAt", now)
                    .append("updatedAt", now));
        }

        var transfers = database.getCollection("treasury_transfers");
        transfers.createIndex(Indexes.ascending("sentAt"),
                new IndexOptions().name("idx_treasury_transfers_sentAt").background(true));
        transfers.createIndex(Indexes.ascending("status"),
                new IndexOptions().name("idx_treasury_transfers_status").background(true));
        transfers.createIndex(Indexes.ascending("ref"),
                new IndexOptions().name("uniq_treasury_transfers_ref").unique(true).background(true));

        var counts = database.getCollection("cash_counts");
        counts.createIndex(Indexes.ascending("accountId", "countedAt"),
                new IndexOptions().name("idx_cash_counts_account_date").background(true));
        counts.createIndex(Indexes.ascending("ref"),
                new IndexOptions().name("uniq_cash_counts_ref").unique(true).background(true));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("treasury_transfers").drop();
        database.getCollection("cash_counts").drop();
    }
}
