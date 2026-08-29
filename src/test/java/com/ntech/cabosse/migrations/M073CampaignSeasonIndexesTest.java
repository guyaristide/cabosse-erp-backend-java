package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
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
 * La base cesse d'interdire deux campagnes ouvertes.
 *
 * <p>Un index unique n'en autorisait qu'une, vestige d'une règle abandonnée.
 * Il ne se voyait pas en test : la migration qui le pose est conditionnée à
 * une capacité que les tenants de test n'ont pas au moment où les migrations
 * tournent. Sur une base réelle, la deuxième campagne ouverte échouait en
 * erreur interne. Ce test le pose donc lui-même, pour éprouver le retrait.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M073CampaignSeasonIndexesTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m073_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static void poseObsoleteIndex(MongoDatabase db) {
        db.getCollection("campaigns").createIndex(
                new Document("status", 1),
                new IndexOptions().name("uniq_campaigns_open").unique(true)
                        .partialFilterExpression(new Document("status", "OPEN")));
    }

    private static List<String> indexNames(MongoDatabase db) {
        return db.getCollection("campaigns").listIndexes()
                .into(new java.util.ArrayList<>()).stream()
                .map(d -> d.getString("name")).toList();
    }

    @Test
    void two_campaigns_of_a_season_can_be_open_at_the_same_time() {
        MongoDatabase database = freshDatabase("ouvertes");
        poseObsoleteIndex(database);

        new M073_CampaignSeasonIndexes().execute(database);

        // La principale n'est pas close le jour où l'intermédiaire démarre.
        database.getCollection("campaigns").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                        .append("campaignYear", 2026).append("kind", "MAIN"),
                new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                        .append("campaignYear", 2027).append("kind", "INTERMEDIATE")));

        assertThat(database.getCollection("campaigns").countDocuments()).isEqualTo(2);
        assertThat(indexNames(database)).doesNotContain("uniq_campaigns_open");
    }

    @Test
    void the_database_still_refuses_two_main_campaigns_on_one_year() {
        MongoDatabase database = freshDatabase("principales");
        new M073_CampaignSeasonIndexes().execute(database);

        database.getCollection("campaigns").insertOne(
                new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                        .append("campaignYear", 2026).append("kind", "MAIN"));

        // Second garde-fou : la validation applicative le dit déjà, l'index
        // le tient même si une écriture passe à côté d'elle.
        assertThatThrownBySecondMain(database);
    }

    private static void assertThatThrownBySecondMain(MongoDatabase database) {
        try {
            database.getCollection("campaigns").insertOne(
                    new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                            .append("campaignYear", 2026).append("kind", "MAIN"));
            org.junit.jupiter.api.Assertions.fail(
                    "une seconde campagne principale sur l'année aurait dû être refusée");
        } catch (com.mongodb.MongoWriteException expected) {
            assertThat(expected.getMessage()).contains("uniq_campaigns_main_per_year");
        }
    }

    @Test
    void several_intermediate_campaigns_share_a_year() {
        MongoDatabase database = freshDatabase("intermediaires");
        new M073_CampaignSeasonIndexes().execute(database);

        database.getCollection("campaigns").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("campaignYear", 2027)
                        .append("kind", "INTERMEDIATE"),
                new Document("_id", UUID.randomUUID()).append("campaignYear", 2027)
                        .append("kind", "INTERMEDIATE")));

        assertThat(database.getCollection("campaigns").countDocuments()).isEqualTo(2);
    }

    @Test
    void replaying_the_migration_changes_nothing() {
        MongoDatabase database = freshDatabase("rejeu");
        poseObsoleteIndex(database);

        new M073_CampaignSeasonIndexes().execute(database);
        new M073_CampaignSeasonIndexes().execute(database);

        assertThat(indexNames(database)).doesNotContain("uniq_campaigns_open");
        assertThat(indexNames(database)).contains("uniq_campaigns_main_per_year");
    }

    /**
     * Le démarrage suivant ne repose pas ce que la migration a retiré.
     *
     * <p>M023 tourne en {@code runAlways} : elle rejoue à chaque
     * démarrage. Elle recréait l'index obsolète, si bien que M073 le
     * retirait puis le voyait revenir au boot suivant. Sur une vraie base,
     * ouvrir une deuxième campagne repartait en erreur interne — alors
     * qu'une saison se joue justement en principale et intermédiaire
     * ouvertes ensemble.</p>
     *
     * <p>Le test précédent ne pouvait pas le voir : il ne rejouait que
     * M073, jamais la migration qui la défaisait.</p>
     */
    @Test
    void the_next_startup_does_not_put_the_obsolete_index_back() {
        // Un vrai tenant : M023 ne fait rien sans la capacité membres, et
        // c'est précisément ce qui masquait le défaut jusqu'ici.
        var tenant = fixtures.createActiveTenant(
                "coop-idx-" + com.ntech.cabosse.test.TestFixtures.randomSlugSuffix(),
                "Coopérative Index");
        tenant.organizationModel =
                com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        MongoDatabase database = mongoClient.getDatabase(tenant.databaseName);
        database.getCollection("campaigns").drop();

        new M023_CreateCampaignsCollection().execute(database, mongoClient);
        new M073_CampaignSeasonIndexes().execute(database);
        // Le démarrage suivant.
        new M023_CreateCampaignsCollection().execute(database, mongoClient);

        assertThat(indexNames(database)).doesNotContain("uniq_campaigns_open");

        // Et deux campagnes ouvertes cohabitent, comme le modèle l'exige.
        database.getCollection("campaigns").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                        .append("code", "CMP-2026-01").append("campaignYear", 2026)
                        .append("kind", "MAIN"),
                new Document("_id", UUID.randomUUID()).append("status", "OPEN")
                        .append("code", "CMP-2027-01").append("campaignYear", 2027)
                        .append("kind", "INTERMEDIATE")));
        assertThat(database.getCollection("campaigns")
                .countDocuments(new Document("status", "OPEN"))).isEqualTo(2);
    }

    /**
     * La réparation doit repasser à chaque démarrage.
     *
     * <p>Corriger la source ne suffisait pas : sur les bases où l'index
     * était déjà revenu, plus rien ne serait repassé pour l'enlever. Sans
     * {@code runAlways}, la campagne close par erreur y restait
     * irrécupérable.</p>
     */
    @Test
    void the_repair_runs_at_every_startup() {
        var changeUnit = M073_CampaignSeasonIndexes.class
                .getAnnotation(io.mongock.api.annotations.ChangeUnit.class);
        assertThat(changeUnit.runAlways())
                .as("M073 répare ce qu'une migration rejouable défaisait : elle doit rejouer aussi")
                .isTrue();
    }
}
