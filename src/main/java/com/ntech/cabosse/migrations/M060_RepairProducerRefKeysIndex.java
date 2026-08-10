package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Migration 060 — refait l'index des clés de rapprochement producteur en
 * index partiel.
 *
 * <p>{@code M052} le posait en {@code sparse}, ce qui ne protège pas du
 * cas réel : un producteur sans carte porte une liste vide, que Mongo
 * indexe sous une clé {@code undefined} commune. Le deuxième producteur
 * sans carte fait échouer l'écriture, et sur un tenant où la migration
 * n'était pas encore passée, l'échec de l'index abandonnait
 * <strong>toute la chaîne</strong> : les migrations suivantes ne
 * s'exécutaient jamais.</p>
 *
 * <p>Là où l'index existe déjà sous l'ancienne forme, on le remplace. Là
 * où {@code M052} vient de le créer correctement, il n'y a rien à
 * faire.</p>
 */
@ChangeUnit(id = "repair_producer_ref_keys_index", order = "060", author = "neiba")
public class M060_RepairProducerRefKeysIndex {

    private static final Logger LOG = Logger.getLogger(M060_RepairProducerRefKeysIndex.class);

    private static final List<String> NAMES =
            List.of("uniq_members_producerRefKeys", "idx_members_producerRefKeys");

    @Execution
    public void execute(MongoDatabase database) {
        var members = database.getCollection("members");

        boolean alreadyPartial = false;
        Set<String> existing = new HashSet<>();
        for (Document index : members.listIndexes()) {
            String name = index.getString("name");
            if (!NAMES.contains(name)) continue;
            existing.add(name);
            if (index.get("partialFilterExpression") != null) alreadyPartial = true;
        }
        if (existing.isEmpty() || alreadyPartial) return;

        for (String name : existing) {
            members.dropIndex(name);
            LOG.infof("M060 : index %s retiré pour être reposé en partiel", name);
        }

        // L'unicité ne se pose que si les clés le permettent. On regarde
        // l'état réel plutôt que de refaire confiance au calcul de M052.
        boolean hasDuplicates = hasDuplicateKeys(database);
        members.createIndex(Indexes.ascending("producerRefKeys"),
                M052_ProducerCardsAsDocuments.producerRefKeysIndexOptions(hasDuplicates));
        LOG.infof("M060 : index des clés producteur reposé (unique=%s)", !hasDuplicates);
    }

    /** Une même clé portée par deux producteurs interdit l'unicité. */
    private static boolean hasDuplicateKeys(MongoDatabase database) {
        var duplicates = database.getCollection("members").aggregate(List.of(
                new Document("$match", new Document("producerRefKeys.0",
                        new Document("$exists", true))),
                new Document("$unwind", "$producerRefKeys"),
                new Document("$group", new Document("_id", "$producerRefKeys")
                        .append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1))),
                new Document("$limit", 1)));
        return duplicates.first() != null;
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Reposer un index défaillant n'aurait aucun sens.
    }
}
