package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/** Migration 010 — collection {@code sales} (M4 Ventes) + indexes. */
@ChangeUnit(id = "create_sales_collection", order = "010", author = "neiba")
public class M010_CreateSalesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("sales").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_sales_ref")
                ),
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.descending("saleDate")
                        ),
                        new IndexOptions().name("idx_sales_status_saleDate")
                ),
                new IndexModel(
                        Indexes.ascending("siteId"),
                        new IndexOptions().name("idx_sales_siteId")
                ),
                new IndexModel(
                        Indexes.ascending("customerId"),
                        new IndexOptions().name("idx_sales_customerId")
                ),
                new IndexModel(
                        // Pour la requête créances : status in (CONFIRMED, DELIVERED) + paymentStatus + dueDate
                        Indexes.compoundIndex(
                                Indexes.ascending("paymentStatus"),
                                Indexes.ascending("dueDate")
                        ),
                        new IndexOptions().name("idx_sales_paymentStatus_dueDate").sparse(true)
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("sales").dropIndexes();
    }
}
