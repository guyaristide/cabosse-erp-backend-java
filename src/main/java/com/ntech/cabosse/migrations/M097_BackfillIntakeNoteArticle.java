package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Migration 097 — l'article derrière le produit d'un bordereau de réception.
 *
 * <p>Le produit n'était qu'un texte libre du carnet, et l'article n'était
 * choisi qu'à la comptabilisation (signalé le 03/11/2026). Les bordereaux
 * déjà saisis dont le libellé est le nom d'un article achetable, à la
 * casse et aux espaces près, reçoivent cet article ; deux articles du
 * même nom ne se départagent pas, le bordereau reste sans article et la
 * comptabilisation choisira, comme avant.</p>
 *
 * <p>{@code runAlways} : rejouée à chaque démarrage, elle ne pose le
 * champ que là où il manque.</p>
 */
@ChangeUnit(id = "backfill_intake_note_article", order = "097", author = "neiba",
        runAlways = true)
public class M097_BackfillIntakeNoteArticle {

    private static final String ARTICLES = "articles";
    private static final String INTAKE_NOTES = "intake_notes";

    @Execution
    public void execute(MongoDatabase database) {
        Map<String, Object> byName = new HashMap<>();
        Map<String, Integer> hits = new HashMap<>();
        for (Document article : database.getCollection(ARTICLES)
                .find(Filters.and(Filters.ne("purchasable", false), Filters.ne("active", false)))
                .projection(new Document("name", 1))) {
            String key = normalize(article.getString("name"));
            if (key.isEmpty()) continue;
            byName.put(key, article.get("_id"));
            hits.merge(key, 1, Integer::sum);
        }
        var notes = database.getCollection(INTAKE_NOTES);
        for (Document note : notes
                .find(Filters.and(Filters.exists("articleId", false),
                        Filters.exists("productLabel", true)))
                .projection(new Document("productLabel", 1))) {
            String key = normalize(note.getString("productLabel"));
            if (hits.getOrDefault(key, 0) != 1) continue;
            notes.updateOne(
                    Filters.and(Filters.eq("_id", note.get("_id")), Filters.exists("articleId", false)),
                    Updates.set("articleId", byName.get(key)));
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace(' ', ' ').replace(' ', ' ').trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Le champ est une lecture du référentiel : le laisser ne trompe
        // personne, et la comptabilisation sait s'en passer.
    }
}
