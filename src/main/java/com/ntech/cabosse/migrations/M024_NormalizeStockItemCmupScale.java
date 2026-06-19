package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.BsonType;
import org.bson.Document;

import java.util.List;

/**
 * Migration 024 — normalise {@code stock_items.cmupFcfa} à 4 décimales.
 *
 * <p>Avant le correctif de {@code StockService.cmupExpression}, le CMUP
 * était calculé par un {@code $divide} Mongo <strong>non arrondi</strong>.
 * Une division non terminante (ex. {@code 264200 / 7}) stockait alors un
 * {@code Decimal128} traînant ses 34 chiffres significatifs. Au mouvement
 * de sortie suivant, le produit {@code quantité × cmup} dépassait la
 * capacité d'encodage de Decimal128 → {@code NumberFormatException:
 * Conversion to Decimal128 would require inexact rounding} et l'OF ne
 * démarrait plus.</p>
 *
 * <p>Le code applique désormais un {@code $round} à {@code CMUP_SCALE = 4}
 * à la source et borne les champs du mouvement avant insertion ; les
 * documents déjà persistés se rétabliraient à leur prochaine entrée. Cette
 * migration nettoie tout de suite l'existant pour éviter d'attendre ce
 * réamorçage et pour garantir une base homogène.</p>
 *
 * <p><strong>Idempotente</strong> : {@code $round} sur une valeur déjà à
 * 4 décimales est sans effet. Rejouable sans risque.</p>
 */
@ChangeUnit(id = "normalize_stock_item_cmup_scale", order = "024", author = "neiba")
public class M024_NormalizeStockItemCmupScale {

    private static final String COLLECTION = "stock_items";
    private static final int CMUP_SCALE = 4;

    @Execution
    public void execute(MongoDatabase database) {
        // Pipeline update : cmupFcfa = round(cmupFcfa, 4) pour toute valeur
        // décimale présente. Filtrage sur le type Decimal128 pour ne jamais
        // passer une valeur non numérique à $round.
        database.getCollection(COLLECTION).updateMany(
                Filters.type("cmupFcfa", BsonType.DECIMAL128),
                List.of(new Document("$set", new Document(
                        "cmupFcfa",
                        new Document("$round", List.of("$cmupFcfa", CMUP_SCALE))
                )))
        );
    }

    /**
     * Pas de rollback exploitable : les valeurs non bornées d'origine sont
     * perdues par l'arrondi et ne pourraient pas être reconstituées. La
     * normalisation est conforme à la précision cible ({@code CMUP_SCALE}),
     * donc l'annulation n'a pas de sens métier — no-op.
     */
    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Intentionnellement vide.
    }
}
