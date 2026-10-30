package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Les fichiers déposés à l'import (DEC-52) : une fiche par dépôt, triée
 * par date, filtrable par domaine. Pas d'expiration : la rétention est
 * illimitée, c'est la décision.
 */
@ChangeUnit(id = "create_import_files", order = "096", author = "neiba")
public class M096_CreateImportFiles {

    static final String COLLECTION = "import_files";

    @Execution
    public void execute(MongoDatabase database) {
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) { exists = true; break; }
        }
        if (!exists) database.createCollection(COLLECTION);

        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(Indexes.descending("uploadedAt"),
                        new IndexOptions().name("idx_import_files_uploadedAt")),
                new IndexModel(Indexes.compoundIndex(
                                Indexes.ascending("domain"), Indexes.descending("uploadedAt")),
                        new IndexOptions().name("idx_import_files_domain_uploadedAt"))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).drop();
    }
}
