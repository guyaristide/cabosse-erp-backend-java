package com.ntech.cabosse.commodity;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Le chargement déjà sorti, quand la vente arrive par un fichier.
 *
 * <p>Un camion de 43 605 kg part sur un bordereau de sortie : le stock
 * tombe à zéro, ce qui est exact. La vente correspondante, importée
 * ensuite, redemandait une seconde sortie pour la même marchandise et
 * repartait sur « Stock insuffisant : 0 disponible, 43 605 demandé ».
 * Le message était juste et n'apprenait rien : les kilos n'étaient pas
 * absents, ils étaient partis (relevé le 23/09/2026 sur SCOOPANAB).</p>
 *
 * <p>Deux corrections, et elles vont ensemble. Le fichier qui nomme le
 * bordereau rattache la vente au chargement, comme le fait déjà l'écran :
 * le poids vient du bordereau, le stock ne bouge plus. Et quand le
 * fichier ne le nomme pas, le refus dit où la marchandise est passée,
 * au lieu de laisser chercher un stock qui a bel et bien existé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommoditySaleImportDispatchNoteTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-bs-" + TestFixtures.randomSlugSuffix(), "Structure Chargements");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Chargements";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 100_000_000);
        return u;
    }

    private record Seed(String siteId, String articleId, String customerId, String noteRef) {}

    /**
     * Un reçu de 3 084 kg au magasin, puis le camion qui les emporte.
     * Au sortir d'ici, le stock est à zéro et la marchandise est partie.
     */
    private Seed seed(UserEntity admin) {
        String siteCode = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\","
                        + "\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        String receiptId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": 50, "weightKg": 3084,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");

        String noteRef = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s",
                          "truckNumber": "CI-4521-AB",
                          "lines": [{ "receiptId": "%s", "netKg": 3084, "bagsCount": 50 }] }
                        """.formatted(LocalDate.now(), siteId, customerId, receiptId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201)
                .extract().path("data.ref");

        return new Seed(siteId, articleId, customerId, noteRef);
    }

    /** La ligne du fichier, dont seule la colonne « N° BS » varie. */
    private String row(Seed s, String dispatchNoteNumber) {
        return """
                [{ "rowNumber": 2, "customerName": "ZAMACOM", "productCode": "Fèves séchées",
                   "date": "%s", "siteId": "%s", "dispatchNoteNumber": "%s",
                   "declaredKg": "3084", "acceptedKg": "3084",
                   "montantFacture": "3084000" }]
                """.formatted(LocalDate.now(), s.siteId(), dispatchNoteNumber);
    }

    @Test
    void le_fichier_qui_nomme_le_bordereau_rattache_la_vente_au_chargement() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        givenAs(admin).contentType("application/json").body(row(s, s.noteRef()))
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200)
                // Aucune seconde sortie : le stock est à zéro et la vente
                // passe quand même, parce qu'elle appelle le chargement.
                .body("data.createdCount", equalTo(1));

        givenAs(admin).when().get("/api/v1/dispatch-notes")
                .then().statusCode(200)
                .body("data.items.find { it.ref == '" + s.noteRef() + "' }.status", equalTo("SOLD"));
    }

    @Test
    void la_casse_de_la_reference_ne_fait_pas_deux_bordereaux() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // Le magasinier recopie « bs-... » aussi souvent que « BS-... ».
        givenAs(admin).contentType("application/json")
                .body(row(s, s.noteRef().toLowerCase()))
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(1));
    }

    @Test
    void le_refus_nomme_le_bordereau_quand_le_fichier_ne_le_nomme_pas() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // « 5 » est le numéro du bordereau papier, pas la référence de
        // l'application : le rattachement ne peut pas se faire, et le
        // refus doit dire pourquoi le stock est à zéro.
        givenAs(admin).contentType("application/json").body(row(s, "5"))
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedRows[0].issues[0].message", containsString(s.noteRef()))
                .body("data.skippedRows[0].issues[0].message", containsString("N° BS"));
    }

    @Test
    void un_manque_de_stock_sans_chargement_reste_un_manque_de_stock() {
        UserEntity admin = admin();
        String siteCode = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\","
                        + "\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201);

        // Rien n'est jamais entré ni sorti : renvoyer vers un chargement
        // qui n'existe pas enverrait chercher une explication fausse.
        givenAs(admin).contentType("application/json")
                .body(row(new Seed(siteId, null, null, null), "5"))
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedRows[0].issues[0].message", not(containsString("N° BS")));
    }
}
