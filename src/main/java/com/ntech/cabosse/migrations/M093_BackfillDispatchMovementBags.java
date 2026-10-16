package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration 093 — les sacs des sorties déjà enregistrées.
 *
 * <p>Jusqu'au 16/10/2026, un chargement sortait des kilos sans ses sacs :
 * le mouvement de stock n'en portait pas, et le registre du magasin
 * gardait un compte de sacs qui ne baissait jamais. Le poids tombait à
 * 18 615 kg quand les sacs restaient à 989 (relevé en production).</p>
 *
 * <p>Le bordereau, lui, a toujours compté ses sacs ligne à ligne : la
 * donnée existe, elle n'était pas reportée. Chaque mouvement de sortie
 * retrouve donc les sacs de la ligne qui l'a produit, dans l'ordre où
 * les deux ont été créés ; une vente sans bordereau retrouve ses sacs
 * acceptés.</p>
 *
 * <p>{@code runAlways} : la correction doit atteindre chaque structure au
 * démarrage suivant, et elle ne retouche jamais un mouvement qui porte
 * déjà ses sacs.</p>
 */
@ChangeUnit(id = "backfill_dispatch_movement_bags", order = "093", author = "neiba",
        runAlways = true)
public class M093_BackfillDispatchMovementBags {

    private static final String MOVEMENTS = "stock_movements";
    private static final String DISPATCH_NOTES = "dispatch_notes";
    private static final String COMMODITY_SALES = "commodity_sales";

    @Execution
    public void execute(MongoDatabase database) {
        MongoCollection<Document> movements = database.getCollection(MOVEMENTS);
        backfillDispatches(database, movements);
        backfillCommoditySales(database, movements);
    }

    /**
     * Les mouvements d'un chargement, appariés aux lignes du bordereau.
     *
     * <p>Les deux ont été écrits dans le même ordre, ligne par ligne :
     * c'est cet ordre qui les rapproche, et non le poids, deux lignes
     * pouvant peser pareil.</p>
     */
    private void backfillDispatches(MongoDatabase database, MongoCollection<Document> movements) {
        for (Document note : database.getCollection(DISPATCH_NOTES).find()) {
            Object noteId = note.get("_id");
            @SuppressWarnings("unchecked")
            List<Document> lines = (List<Document>) note.get("lines");
            if (noteId == null || lines == null || lines.isEmpty()) continue;

            List<Document> outs = movements.find(Filters.and(
                            Filters.eq("sourceType", "DISPATCH_NOTE"),
                            Filters.eq("sourceEntityId", noteId),
                            Filters.in("kind", "OUT", "TRANSFER_OUT")))
                    .sort(new Document("occurredAt", 1).append("_id", 1))
                    .into(new ArrayList<>());
            if (outs.size() != lines.size()) continue;

            for (int i = 0; i < outs.size(); i++) {
                Document movement = outs.get(i);
                if (movement.get("bags") != null) continue;
                Object bags = lines.get(i).get("bagsCount");
                if (bags == null) continue;
                movements.updateOne(Filters.eq("_id", movement.get("_id")),
                        Updates.set("bags", bags));
            }
        }
    }

    /** Une vente sans bordereau sort elle aussi des sacs : les siens. */
    private void backfillCommoditySales(MongoDatabase database,
                                        MongoCollection<Document> movements) {
        for (Document sale : database.getCollection(COMMODITY_SALES)
                .find(Filters.eq("dispatchNoteId", null))) {
            Object saleId = sale.get("_id");
            Document weights = sale.get("weights", Document.class);
            Object bags = weights == null ? null : weights.get("sacsAccepted");
            if (saleId == null || bags == null) continue;
            movements.updateMany(Filters.and(
                            Filters.eq("sourceType", "COMMODITY_SALE"),
                            Filters.eq("sourceEntityId", saleId),
                            Filters.in("kind", "OUT", "TRANSFER_OUT"),
                            Filters.eq("bags", null)),
                    Updates.set("bags", bags));
        }
    }

    /**
     * Rien à défaire : un compte de sacs rendu exact n'a pas d'état
     * antérieur qu'on voudrait retrouver.
     */
    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Volontairement vide.
    }
}
