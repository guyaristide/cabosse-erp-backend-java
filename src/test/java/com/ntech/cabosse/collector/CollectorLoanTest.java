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
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Les prêts aux délégués (demandé le 24/10/2026).
 *
 * <p>Comme un producteur, un délégué peut solliciter un prêt pour un
 * besoin propre, à côté de l'avance qui finance sa collecte. Même circuit
 * de décision et de décaissement, pas de contrepartie attendue en kilos,
 * et le prêt se rembourse sur les livraisons une fois les avances de
 * collecte soldées.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectorLoanTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-loan-" + TestFixtures.randomSlugSuffix(), "Coopérative Prêts");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Prêts";
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

    private record Seed(String delegateId, String siteId, String memberId, String articleId) {}

    private Seed seed(UserEntity admin) {
        String sectionId = givenAs(admin).contentType("application/json")
                .body("{\"code\":\"MEAGUI\",\"name\":\"Section Méagui\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"KONE Adama\",\"collector\":true,\"sectionId\":\"" + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        return new Seed(delegateId, siteId, memberId, articleId);
    }

    /** Demande, approbation, décaissement : l'avance ou le prêt est ouvert. */
    private String disbursed(UserEntity admin, Seed s, String kind, int amount, String purpose) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH",
                          "kind": "%s", "purpose": %s }
                        """.formatted(s.delegateId(), LocalDate.now(), amount, kind,
                        purpose == null ? "null" : "\"" + purpose + "\""))
                .when().post("/api/v1/collector-advances?siteId=" + s.siteId())
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/collector-advances/" + id + "/approve").then().statusCode(200);
        givenAs(admin).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/collector-advances/" + id + "/disburse").then().statusCode(200);
        return id;
    }

    @Test
    void a_loan_follows_the_same_circuit_without_a_counterpart() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        String loanId = disbursed(admin, s, "LOAN", 300_000, "Réparation de la moto");

        givenAs(admin).when().get("/api/v1/collector-advances/" + loanId)
                .then().statusCode(200)
                .body("data.kind", equalTo("LOAN"))
                .body("data.purpose", equalTo("Réparation de la moto"))
                .body("data.status", equalTo("OPEN"))
                // Pas de contrepartie : un prêt ne se mesure pas en kilos.
                .body("data.expectedQuantity", nullValue())
                .body("data.remaining", equalTo(300000));

        // Une avance sans nature reste une avance de collecte.
        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 200000, "paymentMethod": "CASH" }
                        """.formatted(s.delegateId(), LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + s.siteId())
                .then().statusCode(201).body("data.kind", equalTo("COLLECTION"))
                .extract().path("data.id");

        // Dans la file à payer, le prêt porte sa nature.
        givenAs(admin).when().get("/api/v1/treasury/payables?kind=COLLECTOR_LOAN")
                .then().statusCode(200)
                .body("data.page.items.find { it.sourceId == '" + advanceId + "' }", nullValue());
        givenAs(admin).when().get("/api/v1/governance/approvals?kind=COLLECTOR_ADVANCE")
                .then().statusCode(200)
                .body("data.page.items.find { it.sourceId == '" + advanceId + "' }.kind", equalTo("COLLECTOR_ADVANCE"));
    }

    @Test
    void a_delivery_settles_the_collection_advance_before_the_loan() {
        UserEntity admin = admin();
        Seed s = seed(admin);
        String loanId = disbursed(admin, s, "LOAN", 300_000, "Toiture");
        String advanceId = disbursed(admin, s, "COLLECTION", 200_000, null);

        // 500 kg à 1 000 : 500 000 livrés par le délégué. L'avance de
        // collecte (200 000) se solde d'abord, le prêt prend le reste.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 500, "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH", "delegateSupplierId": "%s" }
                        """.formatted(LocalDate.now(), s.memberId(), s.articleId(), s.siteId(), s.delegateId()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/collector-advances/" + advanceId)
                .then().statusCode(200).body("data.remaining", equalTo(0));
        givenAs(admin).when().get("/api/v1/collector-advances/" + loanId)
                .then().statusCode(200).body("data.remaining", equalTo(0));
    }
}
