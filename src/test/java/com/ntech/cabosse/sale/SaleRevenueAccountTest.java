package com.ntech.cabosse.sale;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Compte de produit par article (réf. jeux d'écritures v21 : 701101,
 * 701102…) : le compte porté par la fiche article prime sur le 701000
 * générique lors de la comptabilisation d'une vente.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SaleRevenueAccountTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-vente-" + TestFixtures.randomSlugSuffix(), "Coopérative Ventes");
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

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createCustomer(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Client Négoce\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void sale_credits_article_revenue_account_over_default() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String customerId = createCustomer(admin);

        String articleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "FINISHED_PRODUCT", "name": "Cacao certifié RA",
                          "unit": "kg", "salesRevenueAccount": "701102" }
                        """)
                .when().post("/api/v1/articles").then().statusCode(201)
                .body("data.salesRevenueAccount", equalTo("701102"))
                .extract().path("data.id");

        // Vente créée directement en CONFIRMED (asQuote=false) → comptabilisée.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 10, "unitPriceFcfa": 2000 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=false")
                .then().statusCode(201);

        // Le crédit de produit tombe sur 701102, pas sur le 701000 générique.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].sourceType", equalTo("SALE"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("701102"))
                .body("data.items[0].entries.syscohadaAccount", not(hasItem("701000")));
    }

    @Test
    void sale_falls_back_to_default_revenue_account() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String customerId = createCustomer(admin);

        String articleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "FINISHED_PRODUCT", "name": "Cacao marchand", "unit": "kg" }
                        """)
                .when().post("/api/v1/articles").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 5, "unitPriceFcfa": 1500 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=false")
                .then().statusCode(201);

        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("701000"));
    }
}
