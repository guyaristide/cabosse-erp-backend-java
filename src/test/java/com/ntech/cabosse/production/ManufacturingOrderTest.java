package com.ntech.cabosse.production;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Cycle d'un ordre de fabrication.
 *
 * <p>Le module portait sept points d'entrée en écriture et aucun test. Ce
 * qui compte ici : la matière sort du stock au démarrage, le produit fini y
 * entre à la clôture valorisé au coût matière réellement consommé, et un
 * ordre ne se clôture pas deux fois.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ManufacturingOrderTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private UserEntity admin;
    private String siteId;
    private String cacaoId;
    private String chocolatId;
    private String recipeId;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-of-" + TestFixtures.randomSlugSuffix(), "Coopérative Production");
        migrations.runMigrationsFor(tenant.databaseName);

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

    /** Un atelier prêt à produire : site, matière en stock, recette. */
    private void workshop() {
        admin = tenantAdmin();
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Atelier\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");

        cacaoId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves de cacao\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        chocolatId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"FINISHED_PRODUCT\",\"name\":\"Chocolat noir\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        // 500 kg de fèves à 1 200, soit 600 000 en stock.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "IN", "quantity": 500,
                          "unitPriceFcfa": 1200, "reason": "Amorçage" }
                        """.formatted(cacaoId, siteId))
                .when().post("/api/v1/stocks/movements").then().statusCode(201);

        recipeId = givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "Chocolat noir 70", "finishedProductId": "%s",
                          "yieldQty": 10, "yieldUnit": "kg",
                          "ingredients": [ { "articleId": "%s", "quantity": 12, "unit": "kg" } ] }
                        """.formatted(chocolatId, cacaoId))
                .when().post("/api/v1/recipes").then().statusCode(201).extract().path("data.id");
    }

    private String createOrder(int plannedQty) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "recipeId": "%s", "plannedQty": %d }
                        """.formatted(siteId, recipeId, plannedQty))
                .when().post("/api/v1/production-orders").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void the_material_leaves_the_store_and_the_product_comes_back_valued() {
        workshop();
        String orderId = createOrder(10);

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(200)
                .body("data.status", equalTo("IN_PROGRESS"));

        // 12 kg de fèves consommés à 1 200 : le stock matière descend.
        assertQuantity(cacaoId, 488);

        givenAs(admin).contentType("application/json")
                .body("{\"producedQty\":10}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(200)
                .body("data.status", equalTo("COMPLETED"))
                // 12 × 1 200 = 14 400 de matière pour 10 kg produits.
                .body("data.cmupAtCompletionFcfa", equalTo(1440.0F));

        assertQuantity(chocolatId, 10);
    }

    @Test
    void an_order_cannot_be_completed_twice() {
        workshop();
        String orderId = createOrder(10);
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json").body("{\"producedQty\":10}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(200);

        // Sans quoi le produit fini entrerait deux fois en stock.
        givenAs(admin).contentType("application/json").body("{\"producedQty\":10}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(422)
                .body("statusMessage", containsString("clôturé"));
    }

    @Test
    void an_order_cannot_be_completed_before_it_starts() {
        workshop();
        String orderId = createOrder(10);

        givenAs(admin).contentType("application/json").body("{\"producedQty\":10}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(422);
    }

    @Test
    void a_negative_production_is_refused() {
        workshop();
        String orderId = createOrder(10);
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(200);

        givenAs(admin).contentType("application/json").body("{\"producedQty\":-5}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(anyOf400());
    }

    /**
     * Quantité en stock d'un article sur le site de l'atelier. Le type JSON
     * d'un nombre varie selon qu'il porte des décimales : on compare des
     * valeurs, pas des représentations.
     */
    private void assertQuantity(String articleId, double expected) {
        Number actual = givenAs(admin).queryParam("siteId", siteId)
                .when().get("/api/v1/stocks")
                .then().statusCode(200)
                .extract().path("data.items.find { it.articleId == '%s' }.quantity".formatted(articleId));
        org.junit.jupiter.api.Assertions.assertNotNull(actual, "article absent du stock");
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual.doubleValue(), 0.001);
    }

    private static org.hamcrest.Matcher<Integer> anyOf400() {
        return org.hamcrest.Matchers.allOf(greaterThan(399),
                org.hamcrest.Matchers.lessThan(500));
    }
}
