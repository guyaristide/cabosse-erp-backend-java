package com.ntech.cabosse.producerpurchase;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Montants écrits hors du modèle.
 *
 * <p>Un montant n'arrive pas toujours par l'application : une migration
 * qui pose un zéro, un script d'exploitation, un pipeline d'agrégation
 * écrivent un entier là où le modèle attend un décimal. Le pilote refusait
 * alors de décoder le document, et c'était la liste entière qui tombait,
 * pas la ligne fautive.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class LegacyAmountTypeTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject MongoClient mongoClient;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-typ-" + TestFixtures.randomSlugSuffix(), "Coopérative Types");
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

    @Test
    void an_integer_amount_written_by_a_migration_stays_readable() {
        UserEntity admin = tenantAdmin();

        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kouassi\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        String purchaseId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");

        // Ce que M051 posait sur les reçus antérieurs : un zéro entier.
        mongoClient.getDatabase(tenant.databaseName)
                .getCollection("producer_purchases")
                .updateOne(Filters.eq("_id", java.util.UUID.fromString(purchaseId)),
                        Updates.combine(
                                Updates.set("delegateMarginFcfa", 0),
                                Updates.set("amountPaidFcfa", 100000L),
                                Updates.set("creditImputedFcfa", 0.0)));

        givenAs(admin).queryParam("page", 0).queryParam("perPage", 20)
                .when().get("/api/v1/producer-purchases")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].delegateMarginFcfa", equalTo(0))
                .body("data.items[0].amountPaidFcfa", equalTo(100000));

        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                .body("data.remainderFcfa", equalTo(0.0F));
    }

    @Test
    void the_stored_type_is_normalised_on_the_next_write() {
        UserEntity admin = tenantAdmin();
        var collection = mongoClient.getDatabase(tenant.databaseName)
                .getCollection("supplier_categories");
        java.util.UUID id = java.util.UUID.randomUUID();
        collection.insertOne(new Document("_id", id)
                .append("code", "DELEGUE").append("name", "Délégué collecteur")
                .append("marginMode", "PER_KG")
                .append("marginRate", 25)   // entier, comme le poserait un script
                .append("active", true));

        givenAs(admin).when().get("/api/v1/supplier-categories/" + id)
                .then().statusCode(200)
                .body("data.marginRate", equalTo(25));

        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"DELEGUE\",\"name\":\"Délégué collecteur\",\"marginMode\":\"PER_KG\",\"marginRate\":30}")
                .when().put("/api/v1/supplier-categories/" + id).then().statusCode(200);

        Document stored = collection.find(Filters.eq("_id", id)).first();
        org.junit.jupiter.api.Assertions.assertNotNull(stored);
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.bson.types.Decimal128.class, stored.get("marginRate"),
                "le montant doit être réécrit en Decimal128");
    }
}
