package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

/**
 * Migration 074 — un contrôle qualité peut vivre sans lot de séchage.
 *
 * <p>Le contrôle qualité porte sur de la matière, qui n'est pas toujours
 * sortie d'un séchoir. Il était pourtant lié à un lot de séchage par un
 * champ obligatoire <em>et</em> par un index unique. La règle métier
 * gardée est la bonne : un lot ne porte qu'un seul contrôle, deux verdicts
 * sur la même matière ne se départageant pas. Ce qui change, c'est que
 * l'absence de lot cesse d'être une valeur en double.</p>
 *
 * <p>L'index devient <strong>partiel</strong> plutôt qu'unique tout court.
 * Un index unique sur un champ absent range tous ces documents sous une
 * même clé nulle et refuse le deuxième contrôle autonome : c'est le piège
 * déjà rencontré ailleurs, et {@code sparse} ne suffit pas sur un champ
 * explicitement mis à null. La condition d'existence, elle, écarte
 * réellement les documents sans lot.</p>
 */
@ChangeUnit(id = "quality_check_without_drying", order = "074", author = "neiba")
public class M074_QualityCheckWithoutDrying {

    private static final String CHECKS = "bean_quality_checks";
    private static final String INDEX = "uniq_qc_dryingBatchId";

    @Execution
    public void execute(MongoDatabase database) {
        var collection = database.getCollection(CHECKS);

        // Idempotence : l'index peut avoir déjà été refait, ou n'avoir
        // jamais existé sur un tenant sans la capacité séchage.
        boolean present = false;
        for (Document index : collection.listIndexes()) {
            if (INDEX.equals(index.getString("name"))) present = true;
        }
        if (present) {
            collection.dropIndex(INDEX);
        }

        collection.createIndex(
                new Document("dryingBatchId", 1),
                new IndexOptions().name(INDEX).unique(true)
                        .partialFilterExpression(
                                new Document("dryingBatchId", new Document("$type", "binData"))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        var collection = database.getCollection(CHECKS);
        for (Document index : collection.listIndexes()) {
            if (INDEX.equals(index.getString("name"))) {
                collection.dropIndex(INDEX);
            }
        }
    }
}
