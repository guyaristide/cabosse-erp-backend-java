package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reprise de l'axe campagne sur les opérations qui ne le portaient pas.
 *
 * <p>Trois choses à prouver : le rattachement suit la période et non
 * l'année, un instant se lit comme un jour aussi bien qu'une date ISO, et
 * ce qu'aucune période ne couvre reste sans campagne plutôt que d'être
 * rattaché au hasard.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M067LinkCampaignOnOperationsTest extends AbstractIntegrationTest {

    private static Document campaign(UUID id, String label, int year, String start, String end) {
        return new Document("_id", id)
                .append("label", label)
                .append("campaignYear", year)
                .append("startDate", start)
                .append("endDate", end);
    }

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m067_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void operations_join_the_campaign_covering_their_business_date() {
        MongoDatabase database = freshDatabase("dates");
        UUID principale = UUID.randomUUID();
        UUID intermediaire = UUID.randomUUID();
        database.getCollection("campaigns").insertMany(List.of(
                campaign(principale, "Principale", 2025, "2025-09-01", "2026-02-28"),
                campaign(intermediaire, "Intermédiaire", 2026, "2026-03-01", "2026-08-31")));

        MongoCollection<Document> pieces = database.getCollection("journal_pieces");
        MongoCollection<Document> sales = database.getCollection("sales");
        MongoCollection<Document> expenses = database.getCollection("direct_expenses");

        UUID pieceEnPrincipale = UUID.randomUUID();
        UUID venteEnIntermediaire = UUID.randomUUID();
        UUID depenseHorsPeriode = UUID.randomUUID();
        pieces.insertOne(new Document("_id", pieceEnPrincipale).append("date", "2025-11-12"));
        sales.insertOne(new Document("_id", venteEnIntermediaire).append("saleDate", "2026-05-04"));
        expenses.insertOne(new Document("_id", depenseHorsPeriode).append("expenseDate", "2024-01-10"));

        new M067_LinkCampaignOnOperations().execute(database);

        Document piece = pieces.find(new Document("_id", pieceEnPrincipale)).first();
        assertThat(piece.get("campaignId")).isEqualTo(principale);
        // L'année suit la campagne retenue, pas celle de la date.
        assertThat(piece.getInteger("campaignYear")).isEqualTo(2025);

        assertThat(sales.find(new Document("_id", venteEnIntermediaire)).first().get("campaignId"))
                .isEqualTo(intermediaire);
        assertThat(expenses.find(new Document("_id", depenseHorsPeriode)).first().get("campaignId"))
                .isNull();

        database.drop();
    }

    @Test
    void an_instant_is_read_as_a_day() {
        MongoDatabase database = freshDatabase("instant");
        UUID campagne = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(campagne, "Principale", 2025, "2025-09-01", "2026-02-28"));

        MongoCollection<Document> movements = database.getCollection("stock_movements");
        UUID mouvement = UUID.randomUUID();
        UUID horsPeriode = UUID.randomUUID();
        movements.insertMany(List.of(
                new Document("_id", mouvement)
                        .append("occurredAt", Date.from(Instant.parse("2025-11-12T23:30:00Z"))),
                new Document("_id", horsPeriode)
                        .append("occurredAt", Date.from(Instant.parse("2024-06-01T08:00:00Z")))));

        new M067_LinkCampaignOnOperations().execute(database);

        assertThat(movements.find(new Document("_id", mouvement)).first().get("campaignId"))
                .isEqualTo(campagne);
        assertThat(movements.find(new Document("_id", horsPeriode)).first().get("campaignId"))
                .isNull();

        database.drop();
    }

    @Test
    void a_replay_leaves_already_linked_documents_untouched() {
        MongoDatabase database = freshDatabase("idem");
        UUID campagne = UUID.randomUUID();
        UUID dejaLie = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(campagne, "Principale", 2025, "2025-09-01", "2026-02-28"));

        MongoCollection<Document> orders = database.getCollection("purchase_orders");
        UUID id = UUID.randomUUID();
        orders.insertOne(new Document("_id", id)
                .append("orderDate", "2025-11-12")
                .append("campaignId", dejaLie));

        new M067_LinkCampaignOnOperations().execute(database);
        new M067_LinkCampaignOnOperations().execute(database);

        // Un rattachement corrigé à la main ne doit pas être réécrit.
        assertThat(orders.find(new Document("_id", id)).first().get("campaignId"))
                .isEqualTo(dejaLie);

        database.drop();
    }

    @Test
    void nothing_happens_without_a_campaign_reference() {
        MongoDatabase database = freshDatabase("vide");
        MongoCollection<Document> pieces = database.getCollection("journal_pieces");
        UUID id = UUID.randomUUID();
        pieces.insertOne(new Document("_id", id).append("date", "2025-11-12"));

        new M067_LinkCampaignOnOperations().execute(database);

        assertThat(pieces.find(new Document("_id", id)).first().get("campaignId")).isNull();

        database.drop();
    }
}
