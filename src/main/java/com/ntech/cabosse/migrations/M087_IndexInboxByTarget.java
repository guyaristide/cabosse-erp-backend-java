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
 * Migration 087 — la messagerie interne, lue par son destinataire.
 *
 * <p>La cloche interroge le compteur de non-lus toutes les soixante
 * secondes, par onglet ouvert et par utilisateur. Sa requête filtre sur
 * {@code channel} <strong>et</strong> {@code target}, or aucun des trois
 * index posés par M064 ne portait {@code target} : Mongo ne pouvait
 * utiliser que le préfixe {@code channel}, qui vaut {@code IN_APP} pour
 * toute la messagerie. Chaque passage parcourait donc l'ensemble des
 * notifications de la structure, et le coût grandissait avec elle
 * (relevé en production le 23/09/2026, « MongoDB bouffe énormément de
 * CPU »).</p>
 *
 * <p>{@code createdAt} en descendant complète la paire : la liste de la
 * boîte trie ainsi, et l'index lui évite un tri en mémoire en plus du
 * parcours.</p>
 *
 * <p>{@code runAlways} : une structure provisionnée avant cette livraison
 * doit recevoir l'index au démarrage suivant, sans quoi elle garde le
 * défaut. {@code MigrationIndexes.ensure} rend le passage idempotent et
 * survit à une forme d'index antérieure.</p>
 */
@ChangeUnit(id = "index_inbox_by_target", order = "087", author = "neiba", runAlways = true)
public class M087_IndexInboxByTarget {

    private static final String COLLECTION = "notification_deliveries";
    static final String INDEX = "idx_deliveries_inbox";

    @Execution
    public void execute(MongoDatabase database) {
        MigrationIndexes.ensure(database.getCollection(COLLECTION), List.of(
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("channel"),
                                Indexes.ascending("target"),
                                Indexes.descending("createdAt")),
                        new IndexOptions().name(INDEX))));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(COLLECTION).dropIndex(INDEX);
    }
}
