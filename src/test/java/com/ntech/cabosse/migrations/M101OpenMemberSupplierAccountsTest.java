package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Un compte à chaque sociétaire sans compte, sous la racine choisie
 * (CPT-36) : la reprise ouvre ce qui manque, laisse ce qui existe, et
 * ne fait rien sous une racine absente du plan.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M101OpenMemberSupplierAccountsTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase() {
        return mongoClient.getDatabase(
                "tenant_test_m101_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Document supplier(String name, UUID memberId, String subsidiary) {
        Document d = new Document("_id", UUID.randomUUID()).append("name", name);
        if (memberId != null) d.append("memberId", memberId);
        if (subsidiary != null) d.append("subsidiaryAccount", subsidiary);
        return d;
    }

    @Test
    void les_miroirs_sans_compte_en_recoivent_un_a_la_suite_et_les_autres_restent() {
        MongoDatabase db = freshDatabase();
        db.getCollection("chart_of_accounts").insertOne(
                new Document("_id", UUID.randomUUID()).append("number", "4011100000")
                        .append("label", "Fournisseurs : producteurs").append("family", "TIERS"));
        db.getCollection("chart_of_accounts").insertOne(
                new Document("_id", UUID.randomUUID()).append("number", "4011100007")
                        .append("label", "Producteur déjà ouvert").append("family", "TIERS"));
        UUID konanId = UUID.randomUUID(), diabyId = UUID.randomUUID(), sansParcelleId = UUID.randomUUID(),
                abandonneeId = UUID.randomUUID(), convertiId = UUID.randomUUID();
        db.getCollection("suppliers").insertMany(java.util.List.of(
                supplier("KONAN Yao", konanId, null),
                supplier("DIABY Jean", diabyId, ""),
                supplier("Déjà pourvu", UUID.randomUUID(), "4011100007"),
                supplier("Sans parcelle", sansParcelleId, null),
                supplier("Parcelle abandonnée", abandonneeId, null),
                supplier("Converti", convertiId, null),
                supplier("Garage sans membre", null, null)));
        // Seuls les sociétaires avec parcelle vivante comptent (11/11/2026).
        db.getCollection("parcels").insertMany(java.util.List.of(
                new Document("_id", UUID.randomUUID()).append("memberId", konanId).append("status", "ACTIVE"),
                new Document("_id", UUID.randomUUID()).append("memberId", diabyId).append("status", "FALLOW"),
                new Document("_id", UUID.randomUUID()).append("memberId", abandonneeId).append("status", "ABANDONED"),
                new Document("_id", UUID.randomUUID()).append("memberId", convertiId).append("status", "ACTIVE")));
        db.getCollection("members").insertOne(
                new Document("_id", convertiId).append("convertedToSupplier", true));

        assertEquals(2, M101_OpenMemberSupplierAccounts.open(db, "4011100000"));

        Document konan = db.getCollection("suppliers").find(new Document("name", "KONAN Yao")).first();
        Document diaby = db.getCollection("suppliers").find(new Document("name", "DIABY Jean")).first();
        // À la suite du plus haut déjà posé, jamais un trou repris.
        assertEquals("4011100008", konan.getString("subsidiaryAccount"));
        assertEquals("4011100009", diaby.getString("subsidiaryAccount"));
        assertEquals("4011100000", konan.getString("collectiveAccount"));
        assertEquals("Producteur KONAN Yao", db.getCollection("chart_of_accounts")
                .find(new Document("number", "4011100008")).first().getString("label"));
        for (String name : new String[] {"Garage sans membre", "Sans parcelle", "Parcelle abandonnée", "Converti"}) {
            assertNull(db.getCollection("suppliers").find(new Document("name", name))
                    .first().getString("subsidiaryAccount"), name);
        }

        // Rejouer n'ouvre rien de plus.
        assertEquals(0, M101_OpenMemberSupplierAccounts.open(db, "4011100000"));
    }

    @Test
    void une_racine_absente_du_plan_n_ouvre_rien() {
        MongoDatabase db = freshDatabase();
        db.getCollection("suppliers").insertOne(supplier("KONAN Yao", UUID.randomUUID(), null));
        assertEquals(0, M101_OpenMemberSupplierAccounts.open(db, "401100"));
        assertNull(db.getCollection("suppliers").find(new Document("name", "KONAN Yao"))
                .first().getString("subsidiaryAccount"));
    }
}
