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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Les pesées du bordereau font le poids du reçu (épic magasin, CE-183).
 *
 * <p>Le carnet de la coopérative pèse une livraison en plusieurs passages :
 * brut, décote, net par ligne. Le net seul se paie, sa somme fait le poids
 * du reçu, et la bascule ne crée pas de matière : une décote ou un net qui
 * dépasse le brut est refusé avant tout effet de bord.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PurchaseWeighingTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-pesee-" + TestFixtures.randomSlugSuffix(), "Coopérative Pesées");
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
        fundCashBox(u, 50_000_000);
        return u;
    }

    private record Refs(String memberId, String articleId, String siteId) {}

    private Refs referentials(UserEntity admin) {
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Brou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        return new Refs(memberId, articleId, siteId);
    }

    @Test
    void the_sum_of_net_weights_makes_the_receipt_weight() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);

        // Deux pesées : 800 − 20 = 780 (net calculé) et 700 avec un net
        // saisi à 675, la lecture de la bascule faisant foi. Le poids
        // saisi à 999 est ignoré : quand la bascule a parlé, la saisie
        // directe n'a pas voix au chapitre.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "truckNumber": "CJY1255",
                          "weighings": [
                            { "grossKg": 800, "deductionKg": 20 },
                            { "grossKg": 700, "deductionKg": 25, "netKg": 675 }
                          ],
                          "nbSacs": 23,
                          "weightKg": 999, "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(), refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .body("data.weightKg", equalTo(1455))
                .body("data.amount", equalTo(1455000))
                .body("data.truckNumber", equalTo("CJY1255"))
                .body("data.weighings", hasSize(2))
                .body("data.weighings[0].netKg", equalTo(780))
                .body("data.weighings[1].netKg", equalTo(675));
    }

    @Test
    void the_bag_count_makes_the_deduction_one_kilo_per_bag() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);

        // La règle du carnet, tranchée par l'expert (DEC-34) : « MS » est
        // le nombre de sacs, et le net proposé vaut brut moins sacs. Ses
        // deux exemples, corrigés par lui : 1 500 − 23 = 1 477 et
        // 195 − 3 = 192. Les sacs du reçu se déduisent des pesées.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weighings": [
                            { "grossKg": 1500, "bagsCount": 23 },
                            { "grossKg": 195, "bagsCount": 3 }
                          ],
                          "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(), refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .body("data.weighings[0].netKg", equalTo(1477))
                .body("data.weighings[1].netKg", equalTo(192))
                .body("data.weightKg", equalTo(1669))
                .body("data.nbSacs", equalTo(26));
    }

    @Test
    void a_deduction_larger_than_the_gross_weight_is_refused() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weighings": [ { "grossKg": 100, "deductionKg": 150 } ],
                          "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(), refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(422)
                .body("statusMessage", containsString("150"));
    }

    @Test
    void a_net_weight_above_the_gross_weight_is_refused() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weighings": [ { "grossKg": 100, "netKg": 120 } ],
                          "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(), refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(422)
                .body("statusMessage", containsString("120"));
    }
}
