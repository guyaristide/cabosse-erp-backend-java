package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 043 — référentiels tenant du profil coopérative
 * (backlog COOP-01, COOP-02) : régions ({@code regions}), départements
 * ({@code departments}) et types de pièces d'identité ({@code id_document_types}).
 *
 * <p>Trois listes plates {@code code / name / active}, index unique sur
 * {@code code}. Régions et départements sont <strong>propres au tenant</strong>
 * (décision du 21/07/2026 : mêmes niveau et cohérence, indépendants du catalogue
 * global du plan contrôle) et indépendants entre eux (pas de hiérarchie imposée).
 * <strong>Pas de seed</strong> : chaque tenant construit ses listes à l'usage.</p>
 *
 * <p><strong>Garde-fou</strong> : le tenant interne « platform » partage la base
 * {@code cabosse_control}, où une collection {@code regions} existe déjà (le
 * catalogue global de régions, avec des {@code code} nuls/non uniques). On ne
 * crée donc l'index unique que si la collection est <strong>vide</strong> —
 * jamais sur le catalogue peuplé, ce qui éviterait un E11000 au démarrage.</p>
 */
@ChangeUnit(id = "create_referential_geo_collections", order = "043", author = "neiba")
public class M043_CreateReferentialGeoCollections {

    @Execution
    public void execute(MongoDatabase database) {
        createUniqueCodeIndexIfEmpty(database, "regions", "uniq_regions_code");
        createUniqueCodeIndexIfEmpty(database, "departments", "uniq_departments_code");
        createUniqueCodeIndexIfEmpty(database, "id_document_types", "uniq_id_document_types_code");
    }

    private static void createUniqueCodeIndexIfEmpty(MongoDatabase database, String collection, String indexName) {
        if (database.getCollection(collection).countDocuments() > 0) {
            // Collection déjà peuplée par autre chose (ex. catalogue regions du
            // plan contrôle) : on ne touche pas, l'index unique casserait dessus.
            return;
        }
        database.getCollection(collection).createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name(indexName));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // On ne droppe PAS `regions` : sur la base du plan contrôle c'est le
        // catalogue global, à préserver. On retire seulement l'index créé —
        // en tolérant son absence (non créé si la collection était peuplée).
        try {
            database.getCollection("regions").dropIndex("uniq_regions_code");
        } catch (RuntimeException ignored) {
            // Index inexistant (ex. plan contrôle) : rollback idempotent.
        }
        database.getCollection("departments").drop();
        database.getCollection("id_document_types").drop();
    }
}
