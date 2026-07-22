package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.List;

/**
 * Migration 044 — bascule du nom membre en nom / prénoms (backlog MEM-06).
 *
 * <p>Recopie le champ {@code name} existant dans {@code lastName} pour les
 * membres qui n'ont pas encore le champ (mise à jour par pipeline
 * d'agrégation). {@code firstName} reste absent : l'utilisateur affinera la
 * répartition nom/prénoms à l'édition. Le champ {@code name} est conservé
 * (recomposé ensuite par le service), donc l'affichage reste identique.</p>
 */
@ChangeUnit(id = "backfill_member_name", order = "044", author = "neiba")
public class M044_BackfillMemberName {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("members").updateMany(
                Filters.exists("lastName", false),
                List.of(new Document("$set", new Document("lastName", "$name")))
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("members").updateMany(
                Filters.exists("lastName", true),
                Updates.combine(Updates.unset("lastName"), Updates.unset("firstName"))
        );
    }
}
