package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reprise des années de campagne saisies à la main. Le cas qui compte :
 * une saison ouverte en septembre 2025 et datée « 2026 » par la personne
 * qui l'a créée. La migration la recale sur son ouverture et propage la
 * correction aux flux qui en dénormalisent l'année.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M050DeriveCampaignYearTest extends AbstractIntegrationTest {

    private MongoDatabase db(String name) {
        return mongoClient.getDatabase(name);
    }

    @Test
    void year_is_realigned_on_the_opening_date_and_propagated() {
        String dbName = "tenant_test_m050_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = db(dbName);

        UUID campaignId = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(new Document("_id", campaignId)
                .append("label", "Campagne 2025-2026")
                .append("campaignYear", 2026)          // saisie à la main, ambiguë
                .append("startDate", "2025-09-01")
                .append("endDate", "2026-02-28"));

        MongoCollection<Document> harvests = database.getCollection("harvests");
        UUID harvestId = UUID.randomUUID();
        harvests.insertOne(new Document("_id", harvestId)
                .append("campaignId", campaignId)
                .append("campaignYear", 2026)
                .append("harvestDate", "2025-11-12"));

        MongoCollection<Document> advances = database.getCollection("collector_advances");
        UUID advanceId = UUID.randomUUID();
        advances.insertOne(new Document("_id", advanceId)
                .append("campaignId", campaignId)
                .append("campaignYear", 2026));

        MongoCollection<Document> parcels = database.getCollection("parcels");
        UUID parcelId = UUID.randomUUID();
        parcels.insertOne(new Document("_id", parcelId)
                .append("campaignYields", List.of(
                        new Document("campaignId", campaignId)
                                .append("campaignYear", 2026)
                                .append("estimateKg", 4000))));

        new M050_DeriveCampaignYear().execute(database);

        assertThat(database.getCollection("campaigns").find(new Document("_id", campaignId))
                .first().getInteger("campaignYear")).isEqualTo(2025);

        Document harvest = harvests.find(new Document("_id", harvestId)).first();
        assertThat(harvest.getInteger("campaignYear")).isEqualTo(2025);
        // Le libellé descend sur la récolte : la liste l'affiche sans
        // relire le référentiel.
        assertThat(harvest.getString("campaignLabel")).isEqualTo("Campagne 2025-2026");

        assertThat(advances.find(new Document("_id", advanceId)).first()
                .getInteger("campaignYear")).isEqualTo(2025);

        @SuppressWarnings("unchecked")
        List<Document> yields = (List<Document>) parcels.find(new Document("_id", parcelId))
                .first().get("campaignYields");
        assertThat(yields).hasSize(1);
        assertThat(yields.get(0).containsKey("campaignYear")).isFalse();
        assertThat(yields.get(0).get("campaignId")).isEqualTo(campaignId);
    }

    @Test
    void a_campaign_without_opening_date_is_left_alone() {
        String dbName = "tenant_test_m050b_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = db(dbName);

        UUID campaignId = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(new Document("_id", campaignId)
                .append("label", "Campagne sans date")
                .append("campaignYear", 2024));

        new M050_DeriveCampaignYear().execute(database);

        assertThat(database.getCollection("campaigns").find(new Document("_id", campaignId))
                .first().getInteger("campaignYear")).isEqualTo(2024);
    }
}
