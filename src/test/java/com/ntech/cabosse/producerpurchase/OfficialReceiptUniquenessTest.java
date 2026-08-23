package com.ntech.cabosse.producerpurchase;

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

/**
 * Un reçu officiel ne couvre qu'une opération (ticket CE-28).
 *
 * <p>Le numéro du carnet à souche prouve qu'un paiement a eu lieu. Réutilisé
 * sur deux livraisons, il permettrait de faire passer deux sorties de fonds
 * pour une seule : le second enregistrement doit être refusé, avec la
 * référence de la livraison qui détient déjà le numéro.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class OfficialReceiptUniquenessTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-recu-" + TestFixtures.randomSlugSuffix(), "Coopérative Reçus");
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
        return u;
    }

    private String purchaseBody(String memberId, String articleId, String siteId, String receipt) {
        return """
                { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                  "officialReceiptRef": %s,
                  "weightKg": 100, "guaranteedPricePerKgFcfa": 1000,
                  "paymentMethod": "CASH" }
                """.formatted(LocalDate.now(), memberId, articleId, siteId,
                receipt != null ? "\"" + receipt + "\"" : "null");
    }

    @Test
    void a_receipt_number_cannot_cover_two_deliveries() {
        UserEntity admin = tenantAdmin();
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kouame\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body(purchaseBody(memberId, articleId, siteId, "RC-2026-0451"))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        // Le même numéro sur une seconde livraison est refusé, en nommant
        // la livraison qui le détient.
        givenAs(admin).contentType("application/json")
                .body(purchaseBody(memberId, articleId, siteId, "RC-2026-0451"))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(409)
                .body("statusMessage", containsString("RC-2026-0451"));

        // Sans numéro, aucune contrainte : deux livraisons libres passent.
        givenAs(admin).contentType("application/json")
                .body(purchaseBody(memberId, articleId, siteId, null))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body(purchaseBody(memberId, articleId, siteId, null))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
    }
}
