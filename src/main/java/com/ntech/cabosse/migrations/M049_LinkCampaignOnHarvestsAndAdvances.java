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
 * Migration 049 — rattache les récoltes et les avances aux délégués à une
 * campagne, et non plus à une année isolée.
 *
 * <p>Une année peut porter deux campagnes (principale et intermédiaire) qui
 * n'ont ni la même période ni le même prix de base. La reprise choisit donc
 * la campagne dont la période contient la date de l'opération. À défaut de
 * période exploitable, elle prend la seule campagne de l'année ; s'il y en a
 * plusieurs et qu'aucune ne cadre, le document reste sans campagne plutôt
 * que d'être rattaché au hasard : un rattachement faux est plus coûteux
 * qu'un rattachement absent, qui se voit et se corrige.</p>
 */
@ChangeUnit(id = "link_campaign_on_harvests_and_advances", order = "049", author = "neiba")
public class M049_LinkCampaignOnHarvestsAndAdvances {

    @Execution
    public void execute(MongoDatabase database) {
        Map<Integer, List<Document>> campaignsByYear = loadCampaigns(database);
        if (campaignsByYear.isEmpty()) return;

        backfill(database, "harvests", "harvestDate", campaignsByYear);
        backfill(database, "collector_advances", "advanceDate", campaignsByYear);
    }

    private static Map<Integer, List<Document>> loadCampaigns(MongoDatabase database) {
        Map<Integer, List<Document>> byYear = new HashMap<>();
        for (Document c : database.getCollection("campaigns").find()) {
            Integer year = c.getInteger("campaignYear");
            if (year == null) continue;
            byYear.computeIfAbsent(year, k -> new ArrayList<>()).add(c);
        }
        return byYear;
    }

    private static void backfill(MongoDatabase database, String collectionName, String dateField,
                                 Map<Integer, List<Document>> campaignsByYear) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document doc : collection.find(Filters.exists("campaignId", false))) {
            Integer year = doc.getInteger("campaignYear");
            if (year == null) continue;
            Object campaignId = pickCampaign(campaignsByYear.get(year), doc.get(dateField));
            if (campaignId == null) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", doc.get("_id")),
                    Updates.set("campaignId", campaignId)));
        }

        if (!ops.isEmpty()) collection.bulkWrite(ops);
    }

    /** Campagne dont la période contient la date ; sinon l'unique campagne de l'année. */
    private static Object pickCampaign(List<Document> candidates, Object rawDate) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0).get("_id");

        LocalDate date = parseDate(rawDate);
        if (date == null) return null;
        for (Document c : candidates) {
            LocalDate start = parseDate(c.get("startDate"));
            LocalDate end = parseDate(c.get("endDate"));
            boolean afterStart = start == null || !date.isBefore(start);
            boolean beforeEnd = end == null || !date.isAfter(end);
            if (afterStart && beforeEnd) return c.get("_id");
        }
        return null;
    }

    /** Les dates sont écrites en ISO par le codec ; on tolère l'absence et le format libre. */
    private static LocalDate parseDate(Object raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw.toString().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("harvests").updateMany(
                Filters.exists("campaignId", true), Updates.unset("campaignId"));
        database.getCollection("collector_advances").updateMany(
                Filters.exists("campaignId", true), Updates.unset("campaignId"));
    }
}
