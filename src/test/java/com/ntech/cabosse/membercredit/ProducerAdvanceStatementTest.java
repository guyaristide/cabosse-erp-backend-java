package com.ntech.cabosse.membercredit;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;

/**
 * État des avances producteurs (expert, 09/09/2026) : la même lecture
 * que l'état des délégués, un producteur ayant reçu une avance par
 * ligne, solde = avances moins (livraisons + remboursements retenus).
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProducerAdvanceStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-etat-prod-" + TestFixtures.randomSlugSuffix(), "Coopérative État Producteurs");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "État";
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

    private static void assertAmount(io.restassured.path.json.JsonPath json, String path, String expected) {
        org.junit.jupiter.api.Assertions.assertEquals(
                0, new BigDecimal(expected).compareTo(new BigDecimal(json.getString(path))),
                path + " attendu " + expected + ", obtenu " + json.getString(path));
    }

    @Test
    void a_producer_with_a_disbursed_advance_gets_his_row_and_balance() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String campaign = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Principale", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 1000 }
                        """.formatted(today.minusMonths(2), today.plusMonths(3)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Brou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        // Un second producteur sans avance : il ne doit pas apparaître.
        givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Sans-Avance\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201);

        String creditId = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 100000,
                          "purpose": "Campagne", "campaignId": "%s" }
                        """.formatted(memberId, campaign))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
        String status = givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200).extract().path("data.status");
        if ("PENDING_APPROVAL".equals(status)) {
            givenAs(admin).contentType("application/json").body("{\"note\":\"Accord\"}")
                    .when().post("/api/v1/member-credits/" + creditId + "/approve")
                    .then().statusCode(200);
        }
        givenAs(admin).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(200);

        givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 60, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(today, memberId, articleId, siteId))
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        var statement = givenAs(admin)
                .when().get("/api/v1/member-credits/producers/statement?campaignId=" + campaign)
                .then().statusCode(200)
                .body("data.rows", hasSize(1))
                .extract().jsonPath();
        assertAmount(statement, "data.rows[0].advancedAmount", "100000");
        assertAmount(statement, "data.rows[0].weightKg", "60");
        assertAmount(statement, "data.rows[0].delivered", "60000");
        // Solde : 100 000 − (60 000 + 0 retenu).
        assertAmount(statement, "data.rows[0].advanceBalance", "40000");
        assertAmount(statement, "data.totals.advanceBalance", "40000");

        // L'export répond aussi, au même format que les autres états.
        givenAs(admin).when()
                .get("/api/v1/member-credits/producers/statement/export?campaignId=" + campaign + "&format=csv")
                .then().statusCode(200);
    }
}
