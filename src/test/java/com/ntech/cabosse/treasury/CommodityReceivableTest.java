package com.ntech.cabosse.treasury;

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
import static org.hamcrest.Matchers.hasSize;

/**
 * Les ventes en gros entrent dans la file des créances clients.
 *
 * <p>Trou découvert le 05/09/2026 en cartographiant le tableau de bord de
 * campagne : la file des encaissements ne lisait que les ventes de
 * produits finis, et le chiffre d'affaires de la vue période du tableau
 * Direction les ignorait aussi. Une coopérative qui exporte sa matière
 * lisait « rien à encaisser » avec des millions dehors.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommodityReceivableTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-creance-" + TestFixtures.randomSlugSuffix(), "Coopérative Créances");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Créances";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 10_000_000);
        return u;
    }

    @Test
    void a_partly_paid_commodity_sale_waits_in_the_receivables_queue() {
        UserEntity admin = admin();

        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin créances\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"cre-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Matière exportée\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Brou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Client Export Créances\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");

        // 1 000 kg achetés à 1 000 : le stock qui portera la vente.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 1000, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        // Vendue 900 kg acceptés à 1 500 : 1 350 000 facturés, exonérés.
        String saleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "weights": { "declaredKg": 950, "acceptedKg": 900 }, "pricePerKg": 1500 }
                        """.formatted(LocalDate.now(), customerId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .extract().path("data.id");

        // Le client règle 350 000 : il en doit encore un million.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "paidOn": "%s", "amount": 350000, "method": "CHEQUE",
                          "paymentRef": "Chèque BICICI 4410" }
                        """.formatted(LocalDate.now()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales/" + saleId + "/payments")
                .then().statusCode(200);

        // ─── La créance attend dans la file, sous sa nature propre ───
        givenAs(admin).when().get("/api/v1/treasury/receivables")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].kind", equalTo("COMMODITY_SALE"))
                .body("data.page.items[0].beneficiaryName", equalTo("Client Export Créances"))
                // Reste décimal : TTC moins encaissé, tous deux à virgule.
                .body("data.page.items[0].amount", equalTo(1000000.0F))
                .body("data.totalRemaining", equalTo(1000000.0F));

        // Le filtre par nature la retrouve, et l'autre nature ne la voit pas.
        givenAs(admin).when().get("/api/v1/treasury/receivables?kind=COMMODITY_SALE")
                .then().statusCode(200).body("data.page.items", hasSize(1));
        givenAs(admin).when().get("/api/v1/treasury/receivables?kind=SALE")
                .then().statusCode(200).body("data.page.items", hasSize(0));

        // ─── Le CA de la vue période lit aussi le négoce ───
        // 1 350 000 facturés, marge 400 000 (950 kg sortis au CMUP 1 000).
        var body = givenAs(admin).when().get("/api/v1/executive-dashboard?period=month")
                .then().statusCode(200).extract().jsonPath();
        Number revenue = body.get("data.kpis.find { it.key == 'revenue' }.current");
        Number margin = body.get("data.kpis.find { it.key == 'margin' }.current");
        org.assertj.core.api.Assertions.assertThat(revenue.doubleValue()).isEqualTo(1_350_000d);
        org.assertj.core.api.Assertions.assertThat(margin.doubleValue()).isEqualTo(400_000d);
    }
}
