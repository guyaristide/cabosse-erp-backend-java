package com.ntech.cabosse.collector;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Le reliquat d'avance créditeur se règle sous approbation, ou se
 * reporte (épic magasin, CE-187, circuit de l'expert du 04/09/2026).
 *
 * <p>Un délégué qui livre plus que son avance devient créancier de la
 * coopérative. Le circuit : la caisse demande, le Directeur paie ou
 * reporte. Le paiement débite le compte d'avance contre la trésorerie du
 * moyen réel et ramène le solde à zéro, ce qui interdit de régler deux
 * fois le même reliquat ; le report ne touche à rien et laisse la porte
 * ouverte à une nouvelle demande.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceRefundTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-reliquat-" + TestFixtures.randomSlugSuffix(), "Coopérative Reliquats");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Awa";
        u.lastName = "KONE";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    private record Seed(String delegateId, String campaignId) {}

    /**
     * Un délégué avec 1 000 000 d'avance décaissée et 1 800 000 livrés :
     * son compte est créditeur de 800 000.
     */
    private Seed creditorDelegate(UserEntity admin) {
        String campaignId = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 1000 }
                        """.formatted(LocalDate.now().minusDays(5), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Délégué %s\", \"collector\": true }"
                        .formatted(TestFixtures.randomSlugSuffix()))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 1000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                .then().statusCode(200);

        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Gnamke\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "campaignId": "%s", "delegateSupplierId": "%s",
                          "weightKg": 1800, "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                        campaignId, delegateId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
        return new Seed(delegateId, campaignId);
    }

    @Test
    void the_paid_balance_cannot_be_settled_twice() {
        UserEntity admin = tenantAdmin();
        Seed seed = creditorDelegate(admin);

        // Le compte dit 800 000 : livré 1 800 000 contre 1 000 000 avancés.
        givenAs(admin)
                .queryParam("delegateSupplierId", seed.delegateId())
                .queryParam("campaignId", seed.campaignId())
                .when().get("/api/v1/advance-refunds/credit-balance")
                .then().statusCode(200).body("data", equalTo(800000));

        // Demander plus que le compte est refusé, avec les deux chiffres.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 900000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(422)
                // Le montant sort localisé (« 900 000 ») : on vérifie le
                // sens du refus, pas la mise en forme du nombre.
                .body("statusMessage", containsString("dépasse le solde créditeur"));

        String refundId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 800000,
                          "notes": "Fin de campagne intermédiaire" }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .body("data.creditBalanceAtRequest", equalTo(800000))
                .extract().path("data.id");

        // Une seconde demande pendant que la première court est refusée.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 100000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(422);

        givenAs(admin).contentType("application/json").body("{ \"note\": \"Accord du CA\" }")
                .when().post("/api/v1/advance-refunds/" + refundId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        // Le paiement par chèque : compte d'avance au débit, banque au
        // crédit, la référence du chèque sur la pièce.
        String pieceRef = givenAs(admin).contentType("application/json")
                .body("""
                        { "paymentMethod": "CHEQUE", "paymentRef": "Chèque ECOBANK 0451" }
                        """)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds/" + refundId + "/pay")
                .then().statusCode(200)
                .body("data.status", equalTo("PAID"))
                .body("data.pieceRef", notNullValue())
                .extract().path("data.pieceRef");

        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                // Le compte collectif du tenant : le délégué du test n'a
                // pas de compte individuel sur sa fiche.
                .body("data.items[0].entries.find { it.debit > 0 }.syscohadaAccount",
                        equalTo("409100"))
                .body("data.items[0].entries.find { it.debit > 0 }.debit", equalTo(800000));

        // Le règlement est entré au compte : le solde est retombé à zéro,
        // et le même reliquat ne peut pas se régler une seconde fois.
        givenAs(admin)
                .queryParam("delegateSupplierId", seed.delegateId())
                .queryParam("campaignId", seed.campaignId())
                .when().get("/api/v1/advance-refunds/credit-balance")
                .then().statusCode(200).body("data", equalTo(0));
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 800000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(422);
    }

    @Test
    void a_partial_approval_pays_the_granted_amount_only() {
        UserEntity admin = tenantAdmin();
        Seed seed = creditorDelegate(admin);

        String refundId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 800000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(201)
                .extract().path("data.id");

        // Accorder plus que le demandé est refusé : la demande borne.
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 900000 }")
                .when().post("/api/v1/advance-refunds/" + refundId + "/approve")
                .then().statusCode(422);

        // Le « Partiel » de la V2 : le PCA accorde 500 000 sur 800 000.
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 500000, \"note\": \"Liquidité de la semaine\" }")
                .when().post("/api/v1/advance-refunds/" + refundId + "/approve")
                .then().statusCode(200)
                .body("data.approvedAmount", equalTo(500000))
                .body("data.effectiveAmount", equalTo(500000));

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("""
                        { "paymentMethod": "CASH", "paymentRef": "Pièce de caisse 27",
                          "note": "Vu l'approbation, j'exécute" }
                        """)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds/" + refundId + "/pay")
                .then().statusCode(200)
                .body("data.status", equalTo("PAID"))
                .body("data.paymentNote", equalTo("Vu l'approbation, j'exécute"))
                .extract().path("data.pieceRef");

        // C'est l'accordé qui sort, sur la caisse (pièce de caisse), et le
        // compte ne retombe que d'autant : 300 000 restent dus.
        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                .body("data.items[0].entries.find { it.debit > 0 }.debit", equalTo(500000));
        givenAs(admin)
                .queryParam("delegateSupplierId", seed.delegateId())
                .queryParam("campaignId", seed.campaignId())
                .when().get("/api/v1/advance-refunds/credit-balance")
                .then().statusCode(200).body("data", equalTo(300000));

        // La référence porte le nom de l'expert : DAP.
        givenAs(admin).when().get("/api/v1/advance-refunds/" + refundId)
                .then().body("data.ref", org.hamcrest.Matchers.startsWith("DAP-DEL-"));
    }

    @Test
    void the_report_leaves_the_credit_untouched_and_the_door_open() {
        UserEntity admin = tenantAdmin();
        Seed seed = creditorDelegate(admin);

        String refundId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 800000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(201)
                .extract().path("data.id");

        // Le Directeur reporte : rien à faire comptablement, dit l'expert.
        givenAs(admin).contentType("application/json")
                .body("{ \"note\": \"Report sur les livraisons à venir\" }")
                .when().post("/api/v1/advance-refunds/" + refundId + "/report")
                .then().statusCode(200).body("data.status", equalTo("REPORTED"));

        // Le crédit n'a pas bougé, et une nouvelle demande reste possible.
        givenAs(admin)
                .queryParam("delegateSupplierId", seed.delegateId())
                .queryParam("campaignId", seed.campaignId())
                .when().get("/api/v1/advance-refunds/credit-balance")
                .then().statusCode(200).body("data", equalTo(800000));
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 800000 }
                        """.formatted(seed.delegateId(), seed.campaignId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(201);
    }
}
