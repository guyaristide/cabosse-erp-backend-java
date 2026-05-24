package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 004 — initialise les champs métier ajoutés à {@code articles}
 * pour les documents existants : {@code stockable = true} par défaut.
 *
 * <p>Les autres champs ({@code alertThreshold}, {@code barcode},
 * {@code vatRate}) restent à {@code null} jusqu'à saisie utilisateur ;
 * aucune action de backfill n'est requise pour eux.</p>
 *
 * <p>L'absence du champ {@code stockable} sur un document Mongo
 * désérialiserait en {@code false} (valeur par défaut d'un boolean
 * primitif), ce qui masquerait à tort les sections stock dans l'UI —
 * d'où ce backfill explicite.</p>
 */
@ChangeUnit(id = "backfill_article_stockable", order = "004", author = "neiba")
public class M004_BackfillArticleStockable {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("articles").updateMany(
                Filters.exists("stockable", false),
                Updates.set("stockable", true)
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("articles").updateMany(
                Filters.exists("stockable", true),
                Updates.unset("stockable")
        );
    }
}
