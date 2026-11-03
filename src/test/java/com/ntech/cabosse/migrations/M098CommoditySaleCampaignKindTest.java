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
 * La nature de campagne d'une vente en gros vient de sa campagne.
 *
 * <p>Le « type de campagne » saisi à la main, en français, est remplacé
 * par la nature de la campagne rattachée ; sans campagne, l'ancien texte
 * est lu quand il dit clairement l'une des deux natures, et l'ancien
 * champ disparaît dans tous les cas.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M098CommoditySaleCampaignKindTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase() {
        return mongoClient.getDatabase(
                "tenant_test_m098_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Document sale(UUID id, UUID campaignId, String legacyType) {
        Document d = new Document("_id", id).append("ref", "VC-" + id.toString().substring(0, 4));
        if (campaignId != null) d.append("campaignId", campaignId);
        if (legacyType != null) d.append("campaignType", legacyType);
        return d;
    }

    private static Document saleOf(MongoDatabase db, UUID id) {
        return db.getCollection("commodity_sales").find(new Document("_id", id)).first();
    }

    @Test
    void the_campaign_gives_its_kind_and_the_legacy_text_goes_away() {
        MongoDatabase database = freshDatabase();
        UUID intermediate = UUID.randomUUID();
        database.getCollection("campaigns").insertOne(
                new Document("_id", intermediate).append("label", "Intermédiaire 2026").append("kind", "INTERMEDIATE"));
        UUID linked = UUID.randomUUID();
        UUID orphanMain = UUID.randomUUID();
        UUID orphanUnknown = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        database.getCollection("commodity_sales").insertMany(List.of(
                // Le texte contredisait la campagne : la campagne gagne.
                sale(linked, intermediate, "Principale"),
                sale(orphanMain, null, "principale "),
                sale(orphanUnknown, null, "saison des pluies"),
                sale(untouched, null, null)));

        new M098_BackfillCommoditySaleCampaignKind().execute(database);

        assertThat(saleOf(database, linked).getString("campaignKind")).isEqualTo("INTERMEDIATE");
        assertThat(saleOf(database, orphanMain).getString("campaignKind")).isEqualTo("MAIN");
        assertThat(saleOf(database, orphanUnknown).containsKey("campaignKind")).isFalse();
        for (UUID id : List.of(linked, orphanMain, orphanUnknown, untouched)) {
            assertThat(saleOf(database, id).containsKey("campaignType")).as(id.toString()).isFalse();
        }

        // Rejouée, elle ne change plus rien.
        new M098_BackfillCommoditySaleCampaignKind().execute(database);
        assertThat(saleOf(database, linked).getString("campaignKind")).isEqualTo("INTERMEDIATE");
    }
}
