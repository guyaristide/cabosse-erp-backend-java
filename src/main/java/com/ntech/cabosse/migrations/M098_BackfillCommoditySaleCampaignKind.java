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
 * Migration 098 — la nature de la campagne d'une vente en gros vient de la
 * campagne.
 *
 * <p>La vente demandait un « type de campagne » en texte libre, en
 * français, alors que la campagne porte déjà sa nature (03/11/2026). Les
 * ventes existantes reçoivent la nature de leur campagne ; sans campagne,
 * l'ancien texte est lu quand il est reconnaissable. L'ancien champ est
 * retiré dans les deux cas.</p>
 *
 * <p>{@code runAlways} : rejouée à chaque démarrage, elle ne touche que
 * les ventes qui portent encore l'ancien champ ou n'ont pas de nature.</p>
 */
@ChangeUnit(id = "backfill_commodity_sale_campaign_kind", order = "098", author = "neiba",
        runAlways = true)
public class M098_BackfillCommoditySaleCampaignKind {

    private static final String CAMPAIGNS = "campaigns";
    private static final String SALES = "commodity_sales";

    @Execution
    public void execute(MongoDatabase database) {
        Map<Object, String> kindByCampaign = new HashMap<>();
        for (Document campaign : database.getCollection(CAMPAIGNS)
                .find().projection(new Document("kind", 1))) {
            kindByCampaign.put(campaign.get("_id"), campaign.getString("kind"));
        }
        var sales = database.getCollection(SALES);
        for (Document sale : sales
                .find(Filters.or(Filters.exists("campaignType", true),
                        Filters.and(Filters.exists("campaignKind", false),
                                Filters.exists("campaignId", true))))
                .projection(new Document("campaignId", 1).append("campaignType", 1)
                        .append("campaignKind", 1))) {
            String kind = sale.getString("campaignKind");
            if (kind == null && sale.get("campaignId") != null) {
                kind = kindByCampaign.get(sale.get("campaignId"));
            }
            if (kind == null) kind = fromLegacyLabel(sale.getString("campaignType"));
            var update = kind != null
                    ? Updates.combine(Updates.set("campaignKind", kind), Updates.unset("campaignType"))
                    : Updates.unset("campaignType");
            sales.updateOne(Filters.eq("_id", sale.get("_id")), update);
        }
    }

    /** L'ancien texte libre, quand il dit clairement l'une des deux natures. */
    static String fromLegacyLabel(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("princip") || key.equals("main")) return "MAIN";
        if (key.startsWith("interm") || key.equals("mid") || key.equals("mid-crop")) return "INTERMEDIATE";
        return null;
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Rien à défaire : la nature recopiée reste vraie.
    }
}
