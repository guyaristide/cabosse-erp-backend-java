package com.ntech.cabosse.supplier;

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
import static org.hamcrest.Matchers.nullValue;

/**
 * L'état d'un fournisseur (demandé le 27/10/2026) : depuis sa fiche, tout
 * ce que la structure sait de lui, livraisons, règlements, avances,
 * commandes, réceptions et dépenses, en agrégats et en fil d'opérations,
 * et en fichier.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupplierStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-etat-four-" + TestFixtures.randomSlugSuffix(), "Coopérative État Fournisseur");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
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

    private record Seed(String siteId, String articleId, String sectionId) {}

    private Seed seed(UserEntity admin) {
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String sectionId = givenAs(admin).contentType("application/json")
                .body("{\"code\":\"SEC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() + "\",\"name\":\"Section Ouest\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
        return new Seed(siteId, articleId, sectionId);
    }

    @Test
    void the_statement_adds_up_the_supplier_s_deliveries_and_lists_them() {
        UserEntity admin = admin();
        Seed s = seed(admin);
        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"TRAORE Moussa\",\"cityName\":\"Soubré\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // Deux reçus posés sur l'apporteur, sans producteur nommé.
        for (int kg : new int[]{500, 300}) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "supplierId": "%s", "articleId": "%s", "siteId": "%s",
                              "weightKg": %d, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                            """.formatted(LocalDate.now(), supplierId, s.articleId(), s.siteId(), kg))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        givenAs(admin).when().get("/api/v1/suppliers/" + supplierId + "/statement")
                .then().statusCode(200)
                .body("data.header.name", containsString("TRAORE"))
                .body("data.header.kind", equalTo("SUPPLIER"))
                .body("data.header.cityName", equalTo("Soubré"))
                .body("data.totals.deliveriesCount", equalTo(2))
                .body("data.totals.deliveredKg", equalTo(800))
                .body("data.totals.deliveredAmount", equalTo(800000))
                .body("data.totals.advancesCount", equalTo(0))
                .body("data.operations.size()", greaterThanOrEqualTo(2))
                .body("data.operations[0].type", equalTo("DELIVERY"))
                .body("data.operations[0].sourceType", equalTo("RECEIPT"))
                .body("data.campaigns.size()", greaterThanOrEqualTo(1));

        // En fichier, le fil entier, sous le même titre.
        String csv = givenAs(admin).when().get("/api/v1/suppliers/" + supplierId + "/statement/export?format=csv")
                .then().statusCode(200).contentType(containsString("text/csv")).extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Livraison"), csv);

        // Une campagne qui n'a rien reçu rend un état vide, pas une erreur.
        givenAs(admin).when().get("/api/v1/suppliers/" + supplierId + "/statement?campaignId=" + UUID.randomUUID())
                .then().statusCode(200)
                .body("data.totals.deliveriesCount", equalTo(0))
                .body("data.campaignLabel", nullValue());
    }

    @Test
    void the_statement_of_a_collector_carries_his_advances() {
        UserEntity admin = admin();
        Seed s = seed(admin);
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"KONE Adama\",\"collector\":true,\"sectionId\":\"" + s.sectionId() + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // Demande, approbation, décaissement : l'avance est ouverte.
        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 200000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + s.siteId())
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/approve").then().statusCode(200);
        givenAs(admin).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/disburse").then().statusCode(200);

        givenAs(admin).when().get("/api/v1/suppliers/" + delegateId + "/statement")
                .then().statusCode(200)
                .body("data.header.kind", equalTo("DELEGATE"))
                .body("data.header.sectionId", equalTo(s.sectionId()))
                .body("data.header.sectionName", equalTo("Section Ouest"))
                .body("data.totals.advancesCount", equalTo(1))
                .body("data.totals.advancesDisbursed", equalTo(200000))
                .body("data.totals.advancesOutstanding", equalTo(200000))
                .body("data.advances.size()", equalTo(1))
                .body("data.advances[0].status", equalTo("OPEN"))
                .body("data.operations.find { it.type == 'ADVANCE' }.sourceId", equalTo(advanceId))
                .body("data.operations.find { it.type == 'ADVANCE' }.sourceType", equalTo("ADVANCE"));
    }
}
