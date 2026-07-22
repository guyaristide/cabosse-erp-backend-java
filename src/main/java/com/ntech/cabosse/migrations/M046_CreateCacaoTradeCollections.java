package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 046 — négoce cacao (backlog NEG-02) : contrats de vente
 * ({@code sales_contracts}) et ventes cacao ({@code cacao_sales}).
 * Index unique sur {@code ref} ; index de recherche par campagne / client.
 */
@ChangeUnit(id = "create_cacao_trade_collections", order = "046", author = "neiba")
public class M046_CreateCacaoTradeCollections {

    @Execution
    public void execute(MongoDatabase database) {
        var contracts = database.getCollection("sales_contracts");
        contracts.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_sales_contracts_ref"));
        contracts.createIndex(Indexes.ascending("campaignYear"),
                new IndexOptions().name("idx_sales_contracts_campaign"));
        contracts.createIndex(Indexes.ascending("customerId"),
                new IndexOptions().name("idx_sales_contracts_customer"));

        var sales = database.getCollection("cacao_sales");
        sales.createIndex(Indexes.ascending("ref"),
                new IndexOptions().unique(true).name("uniq_cacao_sales_ref"));
        sales.createIndex(Indexes.ascending("campaignYear"),
                new IndexOptions().name("idx_cacao_sales_campaign"));
        sales.createIndex(Indexes.ascending("customerId"),
                new IndexOptions().name("idx_cacao_sales_customer"));
        sales.createIndex(Indexes.descending("date"),
                new IndexOptions().name("idx_cacao_sales_date"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("sales_contracts").drop();
        database.getCollection("cacao_sales").drop();
    }
}
