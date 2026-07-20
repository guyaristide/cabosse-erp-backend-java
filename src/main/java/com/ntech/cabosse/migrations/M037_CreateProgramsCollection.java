package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Migration 037 — référentiel des programmes budgétaires ({@code programs},
 * backlog CPT-10) et amorçage de la règle d'imputation par centre de coût.
 *
 * <p>Seed illustratif issu des diagrammes v11 (« à valider avec la
 * coopérative »), pleinement éditable. La <strong>règle v8</strong> (COL
 * et AGRO imputés au programme Durabilité, projet « Achat cacao
 * certifié ») est posée en <strong>donnée</strong> sur les centres de
 * coût existants : {@code defaultProgram}/{@code defaultProject}. Elle
 * n'est jamais codée en dur — la coopérative la modifie à sa guise.</p>
 */
@ChangeUnit(id = "create_programs_collection", order = "037", author = "neiba")
public class M037_CreateProgramsCollection {

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("programs");
        coll.createIndex(Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_programs_code"));
        Instant now = Instant.now();

        seedProgram(coll, now, "DURAB", "Durabilité",
                "Promouvoir une filière durable : achats et ventes certifiés, certifications",
                List.of(project("CERT", "Achat cacao certifié (ARS 1000 / RA / FT / Cocolife)"),
                        project("VENTE-CERT", "Ventes de cacao certifié")));
        seedProgram(coll, now, "PROD", "Production",
                "Augmenter la productivité et la qualité : plants améliorés, formation des producteurs",
                List.of(project("PLANTS", "Plants améliorés"),
                        project("FORM", "Formation producteurs")));
        seedProgram(coll, now, "AGRO", "Agroforesterie",
                "Renforcer la résilience climatique : reboisement, restauration des écosystèmes",
                List.of(project("REBOIS", "Reboisement")));
        seedProgram(coll, now, "GENRE", "Genre",
                "Favoriser l'égalité femmes-hommes : activités génératrices de revenus féminines",
                List.of(project("AGR-F", "AGR Femmes")));
        seedProgram(coll, now, "PE", "Protection enfant",
                "Prévenir et éliminer le travail des enfants : remédiation",
                List.of(project("REMED", "Remédiation")));

        // Règle v8, posée en donnée sur les centres de coût (éditable) :
        // les charges des centres COL et AGRO vont au projet cacao certifié.
        var costCenters = database.getCollection("cost_centers");
        for (String cc : List.of("COL", "AGRO")) {
            costCenters.updateOne(
                    Filters.and(Filters.eq("code", cc), Filters.exists("defaultProgram", false)),
                    Updates.combine(
                            Updates.set("defaultProgram", "DURAB"),
                            Updates.set("defaultProject", "CERT"),
                            Updates.set("updatedAt", now)));
        }
    }

    private static Document project(String code, String name) {
        return new Document("code", code).append("name", name).append("active", true);
    }

    private static void seedProgram(com.mongodb.client.MongoCollection<Document> coll,
                                    Instant now, String code, String name, String desc,
                                    List<Document> projects) {
        if (coll.countDocuments(Filters.eq("code", code)) > 0) return;
        coll.insertOne(new Document("_id", UuidCreator.getTimeOrderedEpoch())
                .append("code", code)
                .append("name", name)
                .append("description", desc)
                .append("active", true)
                .append("projects", projects)
                .append("createdAt", now)
                .append("updatedAt", now));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("programs").drop();
    }
}
