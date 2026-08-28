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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Migration 067 — rattache à leur campagne les opérations qui ne portaient
 * pas cet axe.
 *
 * <p>Seuls les flux amont (récoltes, avances, achats aux producteurs,
 * ventes cacao) connaissaient leur campagne. Les ventes, les ordres de
 * fabrication, les mouvements de stock, les dépenses, les bons de commande,
 * les écritures et la trésorerie ne connaissaient que leur date : on
 * pouvait sortir la collecte d'une campagne, pas son compte
 * d'exploitation.</p>
 *
 * <p>La reprise retient la campagne dont la période contient la date métier
 * du document. Si aucune ne la contient, le document reste sans campagne :
 * un rattachement faux fausse silencieusement les états de campagne, alors
 * qu'un trou se voit et se corrige. C'est la règle déjà retenue en 049.</p>
 *
 * <p>Idempotente : elle ne touche que les documents dépourvus de
 * {@code campaignId}, et se rejoue sans effet sur ceux déjà rattachés.</p>
 */
@ChangeUnit(id = "link_campaign_on_operations", order = "067", author = "neiba")
public class M067_LinkCampaignOnOperations {

    /** Collection, champ de date métier, et si ce champ est un instant. */
    private record Target(String collection, String dateField, boolean instant) {}

    private static final List<Target> TARGETS = List.of(
            new Target("journal_pieces", "date", false),
            new Target("stock_movements", "occurredAt", true),
            new Target("sales", "saleDate", false),
            new Target("manufacturing_orders", "scheduledDate", false),
            new Target("direct_expenses", "expenseDate", false),
            new Target("purchase_orders", "orderDate", false),
            new Target("direct_receipts", "receivedDate", false),
            new Target("purchase_requests", "requestDate", false),
            new Target("producer_payments", "date", false),
            new Target("treasury_transfers", "sentAt", false),
            new Target("cash_counts", "countedAt", false),
            new Target("od_drafts", "date", false),
            new Target("inventory_sessions", "openedAt", true));

    @Execution
    public void execute(MongoDatabase database) {
        List<Document> campaigns = database.getCollection("campaigns")
                .find().into(new ArrayList<>());
        if (campaigns.isEmpty()) return;

        for (Target target : TARGETS) {
            backfill(database, target, campaigns);
        }
    }

    private static void backfill(MongoDatabase database, Target target, List<Document> campaigns) {
        MongoCollection<Document> collection = database.getCollection(target.collection());
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document doc : collection.find(Filters.exists("campaignId", false))) {
            LocalDate date = readDate(doc.get(target.dateField()), target.instant());
            Document campaign = covering(campaigns, date);
            if (campaign == null) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", doc.get("_id")),
                    Updates.combine(
                            Updates.set("campaignId", campaign.get("_id")),
                            Updates.set("campaignYear", campaign.getInteger("campaignYear")))));
        }

        if (!ops.isEmpty()) collection.bulkWrite(ops);
    }

    /** Campagne dont la période contient la date, null si aucune ne la contient. */
    private static Document covering(List<Document> campaigns, LocalDate date) {
        if (date == null) return null;
        for (Document c : campaigns) {
            LocalDate start = readDate(c.get("startDate"), false);
            LocalDate end = readDate(c.get("endDate"), false);
            boolean afterStart = start == null || !date.isBefore(start);
            boolean beforeEnd = end == null || !date.isAfter(end);
            if (afterStart && beforeEnd) return c;
        }
        return null;
    }

    /**
     * Une date métier est écrite en ISO par le codec, un instant en date
     * BSON. Le jour d'un instant se lit en UTC : la migration n'a pas accès
     * au fuseau du tenant, et une opération de fin de journée reclassée
     * d'un jour reste dans la bonne campagne sauf à tomber pile sur une
     * borne, cas où elle reste sans rattachement plutôt que d'être fausse.
     */
    private static LocalDate readDate(Object raw, boolean instant) {
        if (raw == null) return null;
        if (raw instanceof Date date) {
            return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC);
        }
        if (raw instanceof Instant at) {
            return LocalDate.ofInstant(at, ZoneOffset.UTC);
        }
        try {
            String text = raw.toString();
            return instant && text.length() > 10
                    ? LocalDate.ofInstant(Instant.parse(text), ZoneOffset.UTC)
                    : LocalDate.parse(text.substring(0, 10));
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        for (Target target : TARGETS) {
            database.getCollection(target.collection()).updateMany(
                    Filters.exists("campaignId", true),
                    Updates.combine(Updates.unset("campaignId"), Updates.unset("campaignYear")));
        }
    }
}
