package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;

/**
 * Migration 036 — référentiel des centres de coût ({@code cost_centers},
 * backlog CPT-09). Seed illustratif issu des diagrammes v11 (« à valider
 * avec la coopérative ») : chaque centre est actif et pleinement
 * éditable ou désactivable par l'admin tenant.
 */
@ChangeUnit(id = "create_cost_centers_collection", order = "036", author = "neiba")
public class M036_CreateCostCentersCollection {

    /** {code, libellé, description}. */
    static final String[][] SEED = {
            {"ADM", "Administration", "Fonctionnement général : direction, comptabilité, finances, RH, logistique, services généraux"},
            {"COL", "Collecte", "Collecte, achat, transport et regroupement de la matière première auprès des producteurs"},
            {"CERT", "Certification", "Obtention, maintien et contrôle des certifications : audits, formations, contrôles internes"},
            {"AGRO", "Agroforesterie", "Systèmes agroforestiers, plants, reboisement, restauration des écosystèmes, suivi des parcelles"},
            {"GEN", "Genre", "Égalité de genre, autonomisation économique des femmes, activités génératrices de revenus féminines"},
            {"PE", "Protection enfant", "Prévention et remédiation du travail des enfants, protection des droits de l'enfant"},
            {"DIG", "Digitalisation", "Équipements informatiques, applications, traçabilité, systèmes d'information, solutions digitales"},
    };

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("cost_centers");
        coll.createIndex(Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_cost_centers_code"));
        Instant now = Instant.now();
        for (String[] s : SEED) {
            boolean exists = coll.countDocuments(Filters.eq("code", s[0])) > 0;
            if (!exists) {
                coll.insertOne(new Document("_id", UuidCreator.getTimeOrderedEpoch())
                        .append("code", s[0])
                        .append("name", s[1])
                        .append("description", s[2])
                        .append("active", true)
                        .append("createdAt", now)
                        .append("updatedAt", now));
            }
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("cost_centers").drop();
    }
}
