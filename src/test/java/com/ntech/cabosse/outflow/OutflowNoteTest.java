package com.ntech.cabosse.outflow;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
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
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Bordereaux de sortie (épic CE-218) : le carnet du magasin s'importe
 * sans écrire le stock (la sortie comptable appartient à la vente), les
 * numéros déjà connus ne doublent pas, et la liste se rapproche seule de
 * la réception (N° BR) et de la vente (N° BS + N° chargement), quel que
 * soit l'ordre des imports.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class OutflowNoteTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-outflow-" + TestFixtures.randomSlugSuffix(), "Coopérative Sorties");
        tenant.organizationModel =
                com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Sorties";
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

    @Test
    void the_carnet_imports_once_and_reconciles_with_intake_and_sale() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String frDate = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Exportateur San Pedro\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");

        // ─── Import du carnet : insécables absorbées, ligne sans date refusée ───
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "campaignLabel": "Principale  2026-2027",
                            "productLabel": "Cacao", "date": " %s ", "movement": " Sortie Stock ",
                            "ref": " BR0254 ", "dispatchNoteNumber": "5", "loadingNumber": "1",
                            "truckNumber": " CJY1255 ", "destination": " San Pedro ",
                            "customerCode": " CL001 ", "customerName": " Exportateur\\u00A0San Pedro ",
                            "lineNumber": "1", "grossWeightKg": "10\\u00A0936",
                            "bagCount": "166", "netWeightKg": "10\\u00A0770" },
                          { "rowNumber": 3, "ref": "BR0255", "date": "%s",
                            "dispatchNoteNumber": "5", "loadingNumber": "1",
                            "customerName": "Client Hors Référentiel",
                            "netWeightKg": "2555" },
                          { "rowNumber": 4, "ref": "BR0256", "netWeightKg": "2555" } ]
                        """.formatted(frDate, frDate))
                .when().post("/api/v1/outflow-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.createdCount", equalTo(2))
                .body("data.rejectedRows", hasSize(1))
                .body("data.rejectedRows[0].rowNumber", equalTo(4));

        // Sans réception ni vente encore : rien de rapproché, client reconnu.
        givenAs(admin).when().get("/api/v1/outflow-notes")
                .then().statusCode(200)
                .body("data", hasSize(2))
                .body("data.find { it.ref == 'BR0254' }.customerId", equalTo(customerId))
                .body("data.find { it.ref == 'BR0254' }.destination", equalTo("San Pedro"))
                .body("data.find { it.ref == 'BR0255' }.customerId", nullValue())
                .body("data.find { it.ref == 'BR0254' }.intakeNoteId", nullValue())
                .body("data.find { it.ref == 'BR0254' }.saleRef", nullValue());

        // Réimporter le même carnet ne double pas les bordereaux.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0254", "date": "%s", "netWeightKg": "10770" },
                          { "rowNumber": 3, "ref": "BR0255", "date": "%s", "netWeightKg": "2555" } ]
                        """.formatted(frDate, frDate))
                .when().post("/api/v1/outflow-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedExistingCount", equalTo(2));

        // ─── La réception du même N° BR apparaît : rapprochement amont ───
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0254", "date": "%s",
                            "movement": "Entrée Stock", "netWeightKg": "10770" } ]
                        """.formatted(frDate))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1));
        givenAs(admin).when().get("/api/v1/outflow-notes")
                .then().statusCode(200)
                .body("data.find { it.ref == 'BR0254' }.intakeNoteId", notNullValue())
                .body("data.find { it.ref == 'BR0255' }.intakeNoteId", nullValue());

        // ─── La vente qui porte N° BS 5 + chargement 1 : rapprochement aval ───
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": 250, "weightKg": 15000,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(today, memberId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
        String saleRef = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "logistics": { "dispatchNoteNumber": " 5 ", "loadingNumber": "1" },
                          "weights": { "declaredKg": 13325, "acceptedKg": 13325, "sacsAccepted": 205 },
                          "pricePerKg": 1200 }
                        """.formatted(today, customerId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .extract().path("data.ref");

        givenAs(admin).when().get("/api/v1/outflow-notes")
                .then().statusCode(200)
                .body("data.find { it.ref == 'BR0254' }.saleRef", equalTo(saleRef))
                .body("data.find { it.ref == 'BR0255' }.saleRef", equalTo(saleRef));
    }
}
