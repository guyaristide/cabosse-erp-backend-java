package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 050 — l'année de campagne cesse d'être saisie.
 *
 * <p>Elle était tapée à la main à la création de la campagne, ce qui la
 * rendait ambiguë pour une saison à cheval sur deux années civiles : deux
 * personnes créant la même campagne pouvaient la dater différemment, et
 * l'âge des plantations du registre variait d'autant. Elle se déduit
 * désormais de la date d'ouverture.</p>
 *
 * <p>La reprise recale l'année sur chaque campagne, puis la propage aux flux
 * qui la dénormalisent pour trier sans jointure. Elle en profite pour
 * inscrire le libellé de la campagne sur les récoltes (affiché en liste) et
 * pour retirer l'année recopiée dans les rendements de parcelle, où elle ne
 * servait qu'à éviter une lecture du référentiel.</p>
 *
 * <p>Idempotente : relancée, elle recalcule les mêmes valeurs.</p>
 */
@ChangeUnit(id = "derive_campaign_year", order = "050", author = "neiba")
public class M050_DeriveCampaignYear {

    /** Flux portant une copie de l'année, à recaler depuis leur campagne. */
    private static final List<String> DENORMALIZED = List.of(
            "harvests", "collector_advances", "producer_purchases",
            "commodity_sales", "sales_contracts");

    @Execution
    public void execute(MongoDatabase database) {
        Map<Object, Document> campaigns = realignCampaigns(database);
        if (campaigns.isEmpty()) return;

        for (String collection : DENORMALIZED) {
            propagate(database, collection, campaigns);
        }
        dropYieldYear(database);
    }

    /** Année de la campagne = année de son ouverture. */
    private static Map<Object, Document> realignCampaigns(MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("campaigns");
        Map<Object, Document> byId = new HashMap<>();
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document c : collection.find()) {
            LocalDate start = parseDate(c.get("startDate"));
            if (start == null) {
                // Sans date d'ouverture, rien à déduire : on garde l'existant.
                byId.put(c.get("_id"), c);
                continue;
            }
            int year = start.getYear();
            Integer current = c.getInteger("campaignYear");
            if (current == null || current != year) {
                ops.add(new UpdateOneModel<>(
                        Filters.eq("_id", c.get("_id")),
                        Updates.set("campaignYear", year)));
                c.put("campaignYear", year);
            }
            byId.put(c.get("_id"), c);
        }

        if (!ops.isEmpty()) collection.bulkWrite(ops);
        return byId;
    }

    private static void propagate(MongoDatabase database, String collectionName,
                                  Map<Object, Document> campaigns) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        boolean carriesLabel = "harvests".equals(collectionName);
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document doc : collection.find(Filters.exists("campaignId", true))) {
            Document campaign = campaigns.get(doc.get("campaignId"));
            if (campaign == null) continue;

            Integer year = campaign.getInteger("campaignYear");
            String label = campaign.getString("label");
            List<org.bson.conversions.Bson> updates = new ArrayList<>();
            if (year != null && !year.equals(doc.getInteger("campaignYear"))) {
                updates.add(Updates.set("campaignYear", year));
            }
            if (carriesLabel && label != null && !label.equals(doc.getString("campaignLabel"))) {
                updates.add(Updates.set("campaignLabel", label));
            }
            if (updates.isEmpty()) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", doc.get("_id")), Updates.combine(updates)));
        }

        if (!ops.isEmpty()) collection.bulkWrite(ops);
    }

    /** Les dates sont écrites en ISO par le codec ; on tolère le format libre. */
    private static LocalDate parseDate(Object raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw.toString().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** L'année recopiée dans chaque rendement de parcelle n'a plus d'emploi. */
    private static void dropYieldYear(MongoDatabase database) {
        database.getCollection("parcels").updateMany(
                Filters.exists("campaignYields", true),
                Updates.unset("campaignYields.$[].campaignYear"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Rien à défaire : les valeurs recalculées sont celles qui auraient
        // dû être écrites. Le libellé dénormalisé, lui, se retire.
        database.getCollection("harvests").updateMany(
                Filters.exists("campaignLabel", true), Updates.unset("campaignLabel"));
    }
}
