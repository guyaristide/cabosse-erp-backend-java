package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Migration 071 — la grille qualité devient un référentiel du tenant.
 *
 * <p>Le grade d'un lot était un enum figé au vocabulaire du cacao ivoirien
 * (GR1, GR2, hors grade). Toute autre filière devait s'y plier : l'hévéa
 * classe en RSS1 à RSS5, l'anacarde raisonne en calibre. Pire, les ventes
 * portaient déjà une <em>seconde</em> nomenclature, saisie en texte libre
 * et vérifiée par personne.</p>
 *
 * <p>La collection est créée avec son index d'unicité, et
 * <strong>semée uniquement là où des données l'utilisent déjà</strong> :
 * un contrôle qualité classé ou une prime de campagne attachée à un grade.
 * Sans cela, ces documents référenceraient un grade absent du référentiel
 * et la validation les refuserait à la première modification.</p>
 *
 * <p>Une structure qui n'a rien classé démarre avec une grille
 * <strong>vide</strong>, et nomme ses grades elle-même. C'est le prix, et
 * l'intérêt, d'un référentiel : personne ne lui dicte le vocabulaire de sa
 * filière.</p>
 */
@ChangeUnit(id = "create_quality_grades", order = "071", author = "neiba")
public class M071_CreateQualityGrades {

    private static final String GRADES = "quality_grades";
    private static final String CHECKS = "bean_quality_checks";
    private static final String CAMPAIGNS = "campaigns";

    /** Libellés des grades cacao, pour les structures qui les utilisent déjà. */
    private static String labelOf(String code) {
        return switch (code) {
            case "GR1" -> "Premier grade";
            case "GR2" -> "Second grade";
            case "HG" -> "Hors grade";
            default -> code;
        };
    }

    /** Le hors grade se range en dernier ; l'ordre alphabétique le mettrait avant GR1. */
    private static int orderOf(String code) {
        return switch (code) {
            case "GR1" -> 10;
            case "GR2" -> 20;
            case "HG" -> 90;
            default -> 50;
        };
    }

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection(GRADES).createIndex(
                new Document("code", 1),
                new IndexOptions().name("uniq_quality_grades_code").unique(true));

        // Ce que les données emploient déjà. Ordonné, pour que deux rejeux
        // produisent la même grille.
        Set<String> used = new LinkedHashSet<>();
        for (Document check : database.getCollection(CHECKS).find(Filters.exists("grade", true))) {
            String grade = check.getString("grade");
            if (grade != null && !grade.isBlank()) used.add(grade.trim());
        }
        for (Document campaign : database.getCollection(CAMPAIGNS)
                .find(Filters.exists("qualityPremiums", true))) {
            Object premiums = campaign.get("qualityPremiums");
            if (!(premiums instanceof List<?> list)) continue;
            for (Object item : list) {
                if (!(item instanceof Document premium)) continue;
                String grade = premium.getString("grade");
                if (grade != null && !grade.isBlank()) used.add(grade.trim());
            }
        }
        if (used.isEmpty()) return;

        // Idempotence : un rejeu ne réécrit pas ce qui est déjà là.
        Set<String> present = new LinkedHashSet<>();
        for (Document existing : database.getCollection(GRADES).find()) {
            String code = existing.getString("code");
            if (code != null) present.add(code);
        }

        Instant now = Instant.now();
        List<Document> toInsert = new ArrayList<>();
        for (String code : used) {
            if (present.contains(code)) continue;
            toInsert.add(new Document("_id", UUID.randomUUID())
                    .append("code", code)
                    .append("label", labelOf(code))
                    .append("sortOrder", orderOf(code))
                    .append("active", true)
                    .append("createdAt", now)
                    .append("updatedAt", now));
        }
        if (!toInsert.isEmpty()) {
            database.getCollection(GRADES).insertMany(toInsert);
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(GRADES).drop();
    }
}
