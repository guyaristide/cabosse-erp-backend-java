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
 * Reprise des récoltes et avances antérieures à la liaison campagne. Le cas
 * qui compte : deux campagnes la même année. Le rattachement se fait alors
 * sur la période, jamais au hasard, et un document qu'aucune période ne
 * couvre reste sans campagne plutôt que d'être mal rattaché.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M049LinkCampaignTest extends AbstractIntegrationTest {

    private MongoDatabase db(String name) {
        return mongoClient.getDatabase(name);
    }

    private static Document campaign(UUID id, String label, int year, String start, String end) {
        return new Document("_id", id)
                .append("label", label)
                .append("campaignYear", year)
                .append("startDate", start)
                .append("endDate", end);
    }

    @Test
    void harvest_is_linked_to_the_campaign_covering_its_date() {
        String dbName = "tenant_test_m049_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = db(dbName);

        UUID principale = UUID.randomUUID();
        UUID intermediaire = UUID.randomUUID();
        database.getCollection("campaigns").insertMany(List.of(
                campaign(principale, "Principale 2026", 2026, "2025-09-01", "2026-02-28"),
                campaign(intermediaire, "Intermédiaire 2026", 2026, "2026-03-01", "2026-08-31")));

        MongoCollection<Document> harvests = database.getCollection("harvests");
        UUID enPrincipale = UUID.randomUUID();
        UUID enIntermediaire = UUID.randomUUID();
        UUID horsPeriode = UUID.randomUUID();
        harvests.insertMany(List.of(
                new Document("_id", enPrincipale).append("campaignYear", 2026)
                        .append("harvestDate", "2025-11-12"),
                new Document("_id", enIntermediaire).append("campaignYear", 2026)
                        .append("harvestDate", "2026-05-04"),
                new Document("_id", horsPeriode).append("campaignYear", 2026)
                        .append("harvestDate", "2026-12-25")));

        new M049_LinkCampaignOnHarvestsAndAdvances().execute(database);

        assertThat(harvests.find(new Document("_id", enPrincipale)).first().get("campaignId"))
                .isEqualTo(principale);
        assertThat(harvests.find(new Document("_id", enIntermediaire)).first().get("campaignId"))
                .isEqualTo(intermediaire);
        // Aucune période ne couvre décembre : on préfère l'absence au hasard.
        assertThat(harvests.find(new Document("_id", horsPeriode)).first().get("campaignId"))
                .isNull();

        database.drop();
    }

    @Test
    void a_single_campaign_for_the_year_is_assigned_without_looking_at_dates() {
        String dbName = "tenant_test_m049_uniq_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = db(dbName);

        UUID unique = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(unique, "Campagne 2025", 2025, null, null));

        MongoCollection<Document> advances = database.getCollection("collector_advances");
        UUID id = UUID.randomUUID();
        advances.insertOne(new Document("_id", id).append("campaignYear", 2025)
                .append("advanceDate", "2025-06-01"));

        new M049_LinkCampaignOnHarvestsAndAdvances().execute(database);

        assertThat(advances.find(new Document("_id", id)).first().get("campaignId"))
                .isEqualTo(unique);

        database.drop();
    }

    @Test
    void an_already_linked_document_is_left_untouched() {
        String dbName = "tenant_test_m049_idem_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = db(dbName);

        UUID campagne = UUID.randomUUID();
        UUID dejaLie = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(campagne, "Campagne 2025", 2025, null, null));
        database.getCollection("harvests").insertOne(
                new Document("_id", UUID.randomUUID()).append("campaignYear", 2025)
                        .append("campaignId", dejaLie).append("harvestDate", "2025-06-01"));

        new M049_LinkCampaignOnHarvestsAndAdvances().execute(database);

        assertThat(database.getCollection("harvests").find().first().get("campaignId"))
                .isEqualTo(dejaLie);

        database.drop();
    }
}
