package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/** Migration 013 — collections {@code bank_statements} + {@code bank_statement_lines}. */
@ChangeUnit(id = "create_bank_reconciliation_collections", order = "013", author = "neiba")
public class M013_CreateBankReconciliationCollections {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("bank_statements").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("bankAccountId"),
                        new IndexOptions().name("idx_statements_bankAccountId")
                ),
                new IndexModel(
                        Indexes.descending("importedAt"),
                        new IndexOptions().name("idx_statements_importedAt_desc")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_statements_status")
                )
        ));

        database.getCollection("bank_statement_lines").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("statementId"),
                        new IndexOptions().name("idx_lines_statementId")
                ),
                new IndexModel(
                        Indexes.ascending("bankAccountId"),
                        new IndexOptions().name("idx_lines_bankAccountId")
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_lines_status")
                ),
                new IndexModel(
                        // Dédoublonnage à l'import : un même hash ne peut exister
                        // qu'une seule fois pour un bankAccount donné.
                        Indexes.compoundIndex(
                                Indexes.ascending("bankAccountId"),
                                Indexes.ascending("sourceHash")
                        ),
                        new IndexOptions().unique(true).name("uniq_lines_account_hash")
                ),
                new IndexModel(
                        // Index utile pour les requêtes "trouve des candidats par montant + date".
                        Indexes.compoundIndex(
                                Indexes.ascending("bankAccountId"),
                                Indexes.ascending("amountFcfa"),
                                Indexes.ascending("operationDate")
                        ),
                        new IndexOptions().name("idx_lines_account_amount_date")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("bank_statements").drop();
        database.getCollection("bank_statement_lines").drop();
    }
}
