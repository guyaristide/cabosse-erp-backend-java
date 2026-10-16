package com.ntech.cabosse.dispatch;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
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
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Du chargement à l'encaissement (épic magasin, CE-195 et CE-194, modèle
 * à main levée de l'expert du 05/09/2026).
 *
 * <p>Le bordereau de sortie appelle des reçus, au besoin partiellement,
 * et sort le stock ligne à ligne sous leurs lots ; le reliquat d'un reçu
 * sert au chargement suivant, jamais deux fois. La vente appelle le
 * bordereau : poids déclaré imposé par lui, aucune seconde sortie de
 * stock, une seule vente par chargement. L'encaissement se constate en
 * trésorerie et laisse le solde au client s'il est partiel.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DispatchAndSaleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;


    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-sortie-" + TestFixtures.randomSlugSuffix(), "Coopérative Sorties");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tenant";
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

    private record Seed(String siteId, String articleId, String customerId,
                        String receipt1, String receipt2) {}

    /** Deux reçus au magasin : 3 084 kg (50 sacs) et 2 290 kg (34 sacs). */
    private Seed seed(UserEntity admin) {
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Exportateur Abidjan\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        String[] receipts = new String[2];
        String[][] deliveries = {{"50", "3084"}, {"34", "2290"}};
        for (int i = 0; i < 2; i++) {
            receipts[i] = givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "nbSacs": %s, "weightKg": %s,
                              "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                            """.formatted(LocalDate.now(), memberId, articleId, siteId,
                            deliveries[i][0], deliveries[i][1]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201)
                    .extract().path("data.id");
        }
        return new Seed(siteId, articleId, customerId, receipts[0], receipts[1]);
    }

    private String createNote(UserEntity admin, Seed s, String linesJson, int expectedStatus) {
        var response = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s",
                          "truckNumber": "CI-4521-AB", "lines": [%s] }
                        """.formatted(LocalDate.now(), s.siteId(), s.customerId(), linesJson))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(expectedStatus);
        return expectedStatus == 201 ? response.extract().path("data.id") : null;
    }

    @Test
    void le_stock_de_depart_se_charge_comme_un_recu() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Exportateur\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        // Le stock de départ d'abord : un couple déjà mouvementé ne
        // s'amorce plus.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 1000,
                                       "unitPrice": 1000, "bags": 20 } ] }
                        """.formatted(siteId, Instant.now(), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        String receiptId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": 10, "weightKg": 500,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");

        // Un bordereau ne se composait que de reçus d'achat, et la
        // matière du stock de départ était inexpédiable (expert,
        // 14/10/2026).
        // Une perte de brassage de 100 kg et 2 sacs : elle sort du stock
        // de départ, qui est la matière en vrac, et non des reçus qui
        // gardent le poids de leur livraison (expert, 15/10/2026).
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "articleId": "%s",
                          "weightKg": 100, "bags": 2, "reason": "Perte de poids pour brassage" }
                        """.formatted(LocalDate.now(), siteId, articleId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stock-corrections").then().statusCode(201);

        String openingId = givenAs(admin)
                .queryParam("siteId", siteId)
                .when().get("/api/v1/stocks/opening/dispatchable")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].weightKg", equalTo(1000.0F))
                .body("data[0].availableKg", equalTo(900.0F))
                .body("data[0].nbSacs", equalTo(18))
                .extract().path("data[0].movementId");

        String noteBody = """
                { "date": "%s", "siteId": "%s", "customerId": "%s",
                  "truckNumber": "CI-4521-AB", "lines": [
                    { "openingMovementId": "%s", "netKg": 400, "bagsCount": 8 },
                    { "receiptId": "%s", "netKg": 100, "bagsCount": 2 } ] }
                """.formatted(LocalDate.now(), siteId, customerId, openingId, receiptId);
        String noteId = givenAs(admin).contentType("application/json").body(noteBody)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).when().get("/api/v1/dispatch-notes/" + noteId)
                .then().statusCode(200)
                .body("data.totalNetKg", equalTo(500))
                .body("data.totalBags", equalTo(10));

        // Le disponible de l'amorçage a fondu d'autant, et pas davantage.
        givenAs(admin)
                .queryParam("siteId", siteId)
                .when().get("/api/v1/stocks/opening/dispatchable")
                .then().statusCode(200)
                .body("data[0].availableKg", equalTo(500.0F));

        // Au-delà du disponible, brassage déduit, le chargement est
        // refusé : 1 000 posés moins 100 de brassage moins 400 chargés.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "lines": [
                          { "openingMovementId": "%s", "netKg": 550 } ] }
                        """.formatted(LocalDate.now(), siteId, openingId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(422);

        // Et une ligne qui désigne deux sources n'a pas de sens.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "lines": [
                          { "openingMovementId": "%s", "receiptId": "%s", "netKg": 10 } ] }
                        """.formatted(LocalDate.now(), siteId, openingId, receiptId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(422);
    }

    @Test
    void the_full_journey_from_loading_to_collection() {
        UserEntity admin = tenantAdmin();
        Seed s = seed(admin);
        // Aperçu : une campagne ouverte, pour que le bordereau la porte.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne principale 2026-2027", "kind": "MAIN",
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 900 }
                        """.formatted(java.time.LocalDate.now().minusMonths(1),
                        java.time.LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201);

        // ─── Le chargement : reçu 1 entier, reçu 2 en partie ───
        String noteId = createNote(admin, s, """
                { "receiptId": "%s", "netKg": 3084, "bagsCount": 50 },
                { "receiptId": "%s", "netKg": 1000, "bagsCount": 15 }
                """.formatted(s.receipt1(), s.receipt2()), 201);
        givenAs(admin).when().get("/api/v1/dispatch-notes/" + noteId)
                .then().statusCode(200)
                .body("data.totalNetKg", equalTo(4084))
                .body("data.totalBags", equalTo(65))
                .body("data.lines", hasSize(2))
                .body("data.status", equalTo("OPEN"));

        // Le stock a baissé d'autant, en une seule fois.
        givenAs(admin).when()
                .get("/api/v1/stocks/" + s.articleId() + "/sites/" + s.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(1290));

        // Le bordereau signé sort en PDF.
        byte[] pdf = givenAs(admin)
                .when().get("/api/v1/dispatch-notes/" + noteId + "/note")
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        // Le bordereau porte sa campagne : c'est elle que le PDF nomme,
        // par son libellé et non par son millésime.
        givenAs(admin).when().get("/api/v1/dispatch-notes/" + noteId)
                .then().statusCode(200).body("data.campaignId", notNullValue());

        // ─── Le reliquat, jamais deux fois ───
        createNote(admin, s, """
                { "receiptId": "%s", "netKg": 2000 }
                """.formatted(s.receipt2()), 422);
        String note2 = createNote(admin, s, """
                { "receiptId": "%s", "netKg": 1290, "bagsCount": 19 }
                """.formatted(s.receipt2()), 201);
        givenAs(admin).when()
                .get("/api/v1/stocks/" + s.articleId() + "/sites/" + s.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(0));

        // ─── L'annulation d'un chargement rend tout ───
        givenAs(admin).contentType("application/json")
                .body("{ \"reason\": \"Camion refusé au pont bascule\" }")
                .when().post("/api/v1/dispatch-notes/" + note2 + "/cancel")
                .then().statusCode(200).body("data.status", equalTo("CANCELLED"));
        givenAs(admin).when()
                .get("/api/v1/stocks/" + s.articleId() + "/sites/" + s.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(1290));

        // ─── La vente appelle le bordereau ───
        String saleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteId": "%s",
                          "weights": { "acceptedKg": 4000, "sacsAccepted": 64 },
                          "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId(), noteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .body("data.dispatchNoteId", equalTo(noteId))
                // Le poids déclaré vient du bordereau, pas de la saisie.
                .body("data.weights.declaredKg", equalTo(4084))
                .body("data.amountInvoicedHt", equalTo(4800000))
                .body("data.pieceRef", notNullValue())
                .extract().path("data.id");

        // Aucune seconde sortie : le stock n'a pas bougé à la vente.
        givenAs(admin).when()
                .get("/api/v1/stocks/" + s.articleId() + "/sites/" + s.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(1290));

        // Le bordereau est vendu, une seule fois.
        givenAs(admin).when().get("/api/v1/dispatch-notes/" + noteId)
                .then().statusCode(200)
                .body("data.status", equalTo("SOLD"))
                .body("data.saleId", equalTo(saleId));
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteId": "%s",
                          "weights": { "acceptedKg": 100 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId(), noteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(422);

        // Un bordereau vendu ne s'annule plus.
        givenAs(admin).contentType("application/json")
                .body("{ \"reason\": \"essai\" }")
                .when().post("/api/v1/dispatch-notes/" + noteId + "/cancel")
                .then().statusCode(422)
                .body("statusMessage", containsString("vendu"));

        // ─── L'encaissement, partiel puis borné ───
        givenAs(admin).contentType("application/json")
                .body("""
                        { "paidOn": "%s", "amount": 2000000, "method": "CHEQUE",
                          "paymentRef": "Chèque SGCI 88412" }
                        """.formatted(LocalDate.now()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales/" + saleId + "/payments")
                .then().statusCode(200)
                .body("data.totalPaid", equalTo(2000000))
                // TTC moins encaissé : décimal en sortie (2 800 000.0).
                .body("data.remainingDue", equalTo(2800000.0F))
                .body("data.payments[0].pieceRef", notNullValue());

        // Encaisser plus que le solde est refusé.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "paidOn": "%s", "amount": 3000000, "method": "BANK_TRANSFER",
                          "paymentRef": "VIR 5521" }
                        """.formatted(LocalDate.now()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales/" + saleId + "/payments")
                .then().statusCode(422);
    }

    @Test
    void la_matiere_recue_d_un_autre_site_se_charge_comme_le_stock_de_depart() {
        UserEntity admin = tenantAdmin();
        Seed s = seed(admin);

        // Le second magasin, et son stock de départ.
        // Le plan d'essai n'autorise qu'un site, et la coopérative en a
        // deux : c'est son abonnement qui ouvre le second.
        tenant.planCode = "enterprise";
        tenants.update(tenant);
        String other = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin secondaire\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"mag2-"
                        + java.util.UUID.randomUUID().toString().substring(0, 8) + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 500, "unitPrice": 1000 } ] }
                        """.formatted(other,
                        LocalDate.now().minusDays(2).atTime(12, 0)
                                .toInstant(java.time.ZoneOffset.UTC), s.articleId()))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        // La coopérative complète le chargement depuis l'autre site.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "fromSiteId": "%s", "toSiteId": "%s",
                          "quantity": 300, "reason": "Complément de chargement" }
                        """.formatted(s.articleId(), other, s.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stocks/transfer").then().statusCode(201);

        // Sans cela, les kilos transférés arrivaient invisibles au
        // chargement (expert, 16/10/2026).
        String transferId = givenAs(admin)
                .queryParam("siteId", s.siteId())
                .when().get("/api/v1/stocks/opening/dispatchable")
                .then().statusCode(200)
                .body("data.find { it.origin == 'TRANSFER' }.availableKg", equalTo(300.0F))
                .extract().path("data.find { it.origin == 'TRANSFER' }.movementId");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "lines": [
                          { "openingMovementId": "%s", "netKg": 300, "bagsCount": 5 } ] }
                        """.formatted(LocalDate.now(), s.siteId(), transferId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201)
                .body("data.totalNetKg", equalTo(300));

        // Et il n'en reste rien à appeler une seconde fois.
        givenAs(admin)
                .queryParam("siteId", s.siteId())
                .when().get("/api/v1/stocks/opening/dispatchable")
                .then().statusCode(200)
                .body("data.find { it.origin == 'TRANSFER' }", nullValue());
    }
}
