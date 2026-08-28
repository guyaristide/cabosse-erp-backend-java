package com.ntech.cabosse.migrations;

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
 * Reprise de la nature des campagnes existantes.
 *
 * <p>Rien en base ne dit laquelle des campagnes d'une année est la
 * principale : c'est une inférence, la première démarrée de l'année, une
 * saison s'ouvrant toujours par sa campagne de gros. Le choix est visible
 * et corrigeable d'un clic ; il ne déplace aucun montant.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M072CampaignKindTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m072_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Document campaign(UUID id, String label, int year, String start) {
        return new Document("_id", id).append("label", label)
                .append("campaignYear", year).append("startDate", start);
    }

    private static String kindOf(MongoDatabase db, UUID id) {
        return db.getCollection("campaigns").find(new Document("_id", id)).first().getString("kind");
    }

    @Test
    void the_first_campaign_of_a_year_becomes_the_main_one() {
        MongoDatabase database = freshDatabase("ordre");
        UUID principale = UUID.randomUUID();
        UUID intermediaire = UUID.randomUUID();
        // Insérées dans le désordre : c'est la date de début qui décide,
        // pas l'ordre d'insertion.
        database.getCollection("campaigns").insertMany(List.of(
                campaign(intermediaire, "Deuxième", 2026, "2026-03-01"),
                campaign(principale, "Première", 2026, "2026-01-01")));

        new M072_CampaignKind().execute(database);

        assertThat(kindOf(database, principale)).isEqualTo("MAIN");
        assertThat(kindOf(database, intermediaire)).isEqualTo("INTERMEDIATE");
    }

    @Test
    void each_year_gets_its_own_main_campaign() {
        MongoDatabase database = freshDatabase("annees");
        UUID a2026 = UUID.randomUUID();
        UUID a2027 = UUID.randomUUID();
        database.getCollection("campaigns").insertMany(List.of(
                campaign(a2026, "Principale 2026", 2026, "2026-09-01"),
                campaign(a2027, "Principale 2027", 2027, "2027-09-01")));

        new M072_CampaignKind().execute(database);

        // La règle porte sur l'année : deux années, deux principales.
        assertThat(kindOf(database, a2026)).isEqualTo("MAIN");
        assertThat(kindOf(database, a2027)).isEqualTo("MAIN");
    }

    @Test
    void a_campaign_already_qualified_is_left_alone() {
        MongoDatabase database = freshDatabase("rejeu");
        UUID id = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(id, "Corrigée à la main", 2026, "2026-01-01")
                        .append("kind", "INTERMEDIATE"));
        UUID autre = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                campaign(autre, "Plus tardive", 2026, "2026-06-01"));

        new M072_CampaignKind().execute(database);

        // Une correction faite à l'écran ne se fait pas écraser par un
        // rejeu de la migration.
        assertThat(kindOf(database, id)).isEqualTo("INTERMEDIATE");
        assertThat(kindOf(database, autre)).isEqualTo("MAIN");
    }

    @Test
    void replaying_the_migration_changes_nothing() {
        MongoDatabase database = freshDatabase("idem");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        database.getCollection("campaigns").insertMany(List.of(
                campaign(first, "Première", 2026, "2026-01-01"),
                campaign(second, "Deuxième", 2026, "2026-06-01")));

        new M072_CampaignKind().execute(database);
        new M072_CampaignKind().execute(database);

        assertThat(kindOf(database, first)).isEqualTo("MAIN");
        assertThat(kindOf(database, second)).isEqualTo("INTERMEDIATE");
    }
}
