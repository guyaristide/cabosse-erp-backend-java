package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migration 063 — le mode « par lot » devient rejouable.
 *
 * <p>Le rejeu chronologique du CMUP (saisie rétroactive, ticket CE-87)
 * a besoin de savoir, mouvement par mouvement, si l'entrée a écrasé le
 * CMUP (livraison délégué valorisée « par lot ») ou s'est pondérée.
 * Le drapeau {@code replacesCmup} est posé au fil de l'eau sur les
 * nouveaux mouvements ; cette migration le reprend sur l'existant :
 * les entrées issues d'achats producteurs rattachés à un délégué,
 * uniquement si la préférence du tenant valorise « par lot » (défaut).
 * Un tenant en pondération n'a jamais écrasé de CMUP : rien à marquer.</p>
 */
@ChangeUnit(id = "backfill_replaces_cmup_flag", order = "063", author = "neiba")
public class M063_BackfillReplacesCmupFlag {

    private static final Logger LOG = Logger.getLogger(M063_BackfillReplacesCmupFlag.class);

    private static final int BATCH = 500;

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!isByLotTenant(database.getName(), client)) return;

        var purchases = database.getCollection("producer_purchases");
        List<UUID> delegatePurchaseIds = new ArrayList<>();
        for (Document p : purchases
                .find(Filters.exists("delegateSupplierId", true))
                .projection(Projections.include("_id"))) {
            Object id = p.get("_id");
            if (id instanceof UUID uuid) delegatePurchaseIds.add(uuid);
        }
        if (delegatePurchaseIds.isEmpty()) return;

        var movements = database.getCollection("stock_movements");
        long marked = 0;
        for (int i = 0; i < delegatePurchaseIds.size(); i += BATCH) {
            List<UUID> chunk = delegatePurchaseIds.subList(
                    i, Math.min(i + BATCH, delegatePurchaseIds.size()));
            marked += movements.updateMany(
                    Filters.and(
                            Filters.eq("kind", "IN"),
                            Filters.eq("sourceType", "PRODUCER_PURCHASE"),
                            Filters.in("sourceEntityId", chunk)
                    ),
                    new Document("$set", new Document("replacesCmup", true))
            ).getModifiedCount();
        }
        if (marked > 0) {
            LOG.infof("M063 : %d mouvement(s) marqué(s) « par lot » (%s)",
                    marked, database.getName());
        }
    }

    private static boolean isByLotTenant(String databaseName, MongoClient client) {
        Document tenant = client.getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", databaseName))
                .projection(Projections.include("preferences.collectorDeliveryValuation"))
                .first();
        if (tenant == null) return false;
        Document prefs = tenant.get("preferences", Document.class);
        String valuation = prefs != null ? prefs.getString("collectorDeliveryValuation") : null;
        // Miroir de TenantPreferences.collectorDeliveryValuationOrDefault() :
        // absent ou vide = BY_LOT.
        return valuation == null || valuation.isBlank() || "BY_LOT".equals(valuation);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("stock_movements").updateMany(
                Filters.eq("replacesCmup", true),
                new Document("$unset", new Document("replacesCmup", ""))
        );
    }
}
