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

import static org.hamcrest.Matchers.equalTo;

/**
 * L'avertissement de solde insuffisant au décaissement d'une avance.
 *
 * <p>Demandé par l'utilisateur le 06/09/2026 : un chèque sans provision
 * expose la coopérative auprès de sa banque. Le refus porte un code et se
 * passe outre explicitement, une banque pouvant autoriser le découvert ;
 * le message ne cite aucun chiffre, le solde bancaire étant une
 * information réservée. La caisse, elle, garde son refus absolu : on ne
 * sort pas d'un tiroir les billets qu'il ne contient pas.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DisbursementBalanceGuardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-provision-" + TestFixtures.randomSlugSuffix(), "Coopérative Provision");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Provision";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 5_000_000);
        return u;
    }

    private String approvedAdvance(UserEntity admin, String delegateId, int amount, String method) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "%s" }
                        """.formatted(delegateId, LocalDate.now(), amount, method))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        return id;
    }

    @Test
    void an_unfunded_cheque_warns_and_yields_only_to_an_explicit_override() {
        UserEntity admin = admin();
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Provision\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // ─── La banque est vide : le chèque avertit, avec son code ───
        String cheque = approvedAdvance(admin, delegateId, 200_000, "CHEQUE");
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CHEQUE\" }")
                .when().post("/api/v1/collector-advances/" + cheque + "/disburse")
                .then().statusCode(422)
                .body("errorCode", equalTo("TREASURY_INSUFFICIENT"));

        // Passer outre est un geste explicite : le découvert s'assume.
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CHEQUE\", \"acknowledgeInsufficientBalance\": true }")
                .when().post("/api/v1/collector-advances/" + cheque + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("OPEN"));

        // ─── La caisse, elle, ne cède jamais : au-delà de son solde,
        // même le passer-outre est refusé (garde absolue des espèces) ───
        String cash = approvedAdvance(admin, delegateId, 8_000_000, "CASH");
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CASH\", \"acknowledgeInsufficientBalance\": true }")
                .when().post("/api/v1/collector-advances/" + cash + "/disburse")
                .then().statusCode(422);
    }

    @Test
    void a_member_credit_by_cheque_warns_the_same_way() {
        UserEntity admin = admin();
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Yao\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String creditId = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "CREDIT", "amount": 300000, "purpose": "Toiture" }
                        """.formatted(memberId))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
        String status = givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200).extract().path("data.status");
        if ("PENDING_APPROVAL".equals(status)) {
            givenAs(admin).contentType("application/json").body("{\"note\":\"Accord\"}")
                    .when().post("/api/v1/member-credits/" + creditId + "/approve")
                    .then().statusCode(200);
        }

        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CHEQUE\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(422)
                .body("errorCode", equalTo("TREASURY_INSUFFICIENT"));
        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CHEQUE\",\"acknowledgeInsufficientBalance\":true}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("DISBURSED"));
    }

    @Test
    void an_advance_refund_by_cheque_warns_the_same_way() {
        UserEntity admin = admin();

        // Un délégué créditeur : avance en espèces, livraisons qui la dépassent.
        String campaignId = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne provision", "kind": "MAIN",
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 1000 }
                        """.formatted(LocalDate.now().minusDays(5), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Créditeur Provision\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        String advanceId = approvedAdvance(admin, delegateId, 1_000_000, "CASH");
        givenAs(admin).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                .then().statusCode(200);
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Gba\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin provision\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"prov-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Matière provision\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "campaignId": "%s", "delegateSupplierId": "%s",
                          "weightKg": 1800, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                        campaignId, delegateId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        // Le reliquat demandé puis approuvé se règle par chèque : même garde.
        String refundId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "campaignId": "%s", "amount": 500000 }
                        """.formatted(delegateId, campaignId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/advance-refunds/" + refundId + "/approve")
                .then().statusCode(200);

        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CHEQUE\", \"paymentRef\": \"CHQ-901\" }")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds/" + refundId + "/pay")
                .then().statusCode(422)
                .body("errorCode", equalTo("TREASURY_INSUFFICIENT"));
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CHEQUE\", \"paymentRef\": \"CHQ-901\", \"acknowledgeInsufficientBalance\": true }")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/advance-refunds/" + refundId + "/pay")
                .then().statusCode(200)
                .body("data.status", equalTo("PAID"));
    }
}
