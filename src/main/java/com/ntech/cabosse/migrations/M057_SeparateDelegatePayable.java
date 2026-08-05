package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Migration 057 — la dette envers un délégué cesse d'être confondue avec
 * celle envers un producteur.
 *
 * <p>Un reçu apporté par un délégué et pas entièrement réglé créait un
 * reliquat au compte des producteurs. Or le délégué a déjà payé le
 * producteur sur son avance : c'est lui le créancier. Confondre les deux
 * rendait la question « à qui devons-nous cet argent ? » sans réponse, et
 * un règlement soldait la mauvaise dette.</p>
 *
 * <p>Les comptes de tiers utilisés par les reçus, les avances et les
 * crédits n'avaient jamais été semés : ils apparaissaient dans les
 * écritures sans exister au plan, donc sans libellé dans la balance ni le
 * grand-livre.</p>
 */
@ChangeUnit(id = "separate_delegate_payable", order = "057", author = "neiba")
public class M057_SeparateDelegatePayable {

    private static final String PRODUCER_PAYABLE = "401100";
    private static final String DELEGATE_PAYABLE = "401200";

    private static final String[][] ACCOUNTS = {
            {PRODUCER_PAYABLE, "Fournisseurs : producteurs", "FOURNISSEURS"},
            {DELEGATE_PAYABLE, "Fournisseurs : délégués collecteurs", "FOURNISSEURS"},
            {"409100", "Avances aux délégués collecteurs", "FOURNISSEURS"},
            {"409200", "Créances sur producteurs : crédits et avances", "FOURNISSEURS"},
    };

    @Execution
    public void execute(MongoDatabase database) {
        var chart = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        for (String[] row : ACCOUNTS) {
            if (chart.find(Filters.eq("number", row[0])).first() != null) continue;
            chart.insertOne(new Document("_id", UUID.randomUUID())
                    .append("number", row[0])
                    .append("label", row[1])
                    .append("family", row[2])
                    .append("active", true)
                    .append("createdAt", now)
                    .append("updatedAt", now));
        }

        // Reliquats déjà comptabilisés : on ne peut les reclasser qu'en
        // repassant par le reçu, seul porteur du délégué. Les pièces des
        // reçus sans délégué gardent leur compte, leur intention était
        // juste.
        var purchases = database.getCollection("producer_purchases");
        var pieces = database.getCollection("journal_pieces");
        for (Document receipt : purchases.find(Filters.exists("delegateSupplierId", true))) {
            UUID purchaseId = receipt.get("_id", UUID.class);
            if (purchaseId == null) continue;
            pieces.updateMany(
                    Filters.and(
                            Filters.eq("sourceType", "PRODUCER_PURCHASE"),
                            Filters.eq("sourceId", purchaseId),
                            Filters.eq("entries.syscohadaAccount", PRODUCER_PAYABLE)),
                    Updates.set("entries.$[leg].syscohadaAccount", DELEGATE_PAYABLE),
                    new com.mongodb.client.model.UpdateOptions().arrayFilters(
                            List.of(Filters.eq("leg.syscohadaAccount", PRODUCER_PAYABLE))));
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Le sens d'origine ne se retrouve pas : deux dettes distinctes
        // étaient écrasées sur un même compte.
    }
}
