package com.ntech.cabosse.members;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * L'état d'un membre (demandé le 26/10/2026) : depuis sa fiche, tout ce
 * que la structure sait de lui, livraisons, règlements, crédits, récoltes
 * et parcelles, en agrégats et en fil d'opérations, et en fichier.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-etat-" + TestFixtures.randomSlugSuffix(), "Coopérative État");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Les parcelles relèvent de la production agricole : sans
        // l'activité, le droit n'existe pas pour personne.
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity = new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
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
        fundCashBox(u, 50_000_000);
        return u;
    }

    @Test
    void the_statement_adds_up_the_member_s_deliveries_and_lists_them() {
        UserEntity admin = admin();
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"KOUASSI\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{\"memberId\":\"" + memberId + "\",\"name\":\"Parcelle Nord\",\"surfaceHa\":2.5,"
                        + "\"gpsCenter\":[-7.5,6.8],\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/parcels").then().statusCode(201);

        for (int kg : new int[]{500, 300}) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "weightKg": %d, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                            """.formatted(LocalDate.now(), memberId, articleId, siteId, kg))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        givenAs(admin).when().get("/api/v1/members/" + memberId + "/statement")
                .then().statusCode(200)
                .body("data.header.name", containsString("KOUASSI"))
                .body("data.header.parcelCount", equalTo(1))
                .body("data.header.totalSurfaceHa", equalTo(2.5f))
                .body("data.totals.deliveriesCount", equalTo(2))
                .body("data.totals.deliveredKg", equalTo(800))
                .body("data.totals.deliveredAmount", equalTo(800000))
                .body("data.operations.size()", greaterThanOrEqualTo(2))
                .body("data.operations[0].type", equalTo("DELIVERY"))
                .body("data.campaigns.size()", greaterThanOrEqualTo(1));

        // En fichier, le fil entier, sous le même titre.
        String csv = givenAs(admin).when().get("/api/v1/members/" + memberId + "/statement/export?format=csv")
                .then().statusCode(200).contentType(containsString("text/csv")).extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Livraison"), csv);

        // Une campagne qui n'a rien reçu rend un état vide, pas une erreur.
        givenAs(admin).when().get("/api/v1/members/" + memberId + "/statement?campaignId=" + UUID.randomUUID())
                .then().statusCode(200).body("data.totals.deliveriesCount", equalTo(0));
    }
}
