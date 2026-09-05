package com.ntech.cabosse.stock;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Requalification d'une nature à l'autre.
 *
 * <p>Le cas de terrain : du cacao acheté pour être revendu en l'état, dont
 * une partie finit en fabrication. Rien ne bouge physiquement, mais la
 * charge doit quitter le compte des marchandises pour celui des matières
 * premières, sinon la balance raconte autre chose que ce qui s'est passé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockReclassificationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-requal-" + TestFixtures.randomSlugSuffix(), "Coopérative Requalification");
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

    private String createArticle(UserEntity admin, String type, String name) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "%s", "name": "%s", "unit": "kg" }
                        """.formatted(type, name))
                .when().post("/api/v1/articles").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    /** Entrée initiale par réception directe, pour disposer d'un CMUP. */
    private void receive(UserEntity admin, String siteId, String articleId, int qty, int unitPrice) {
        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Négociant " + java.util.UUID.randomUUID().toString().substring(0, 6) + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "receivedDate": "%s",
                          "lines": [ { "supplierId": "%s", "quantity": %d, "unitPrice": %d } ] }
                        """.formatted(articleId, LocalDate.now(), supplierId, qty, unitPrice))
                .when().post("/api/v1/direct-receipts?siteId=" + siteId).then().statusCode(201);
    }

    @Test
    void a_share_of_merchandise_becomes_raw_material() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String merchandise = createArticle(admin, "MERCHANDISE", "Cacao acheté pour revente");
        String rawMaterial = createArticle(admin, "RAW_MATERIAL", "Cacao à transformer");

        receive(admin, siteId, merchandise, 1000, 1500);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "fromArticleId": "%s", "toArticleId": "%s", "siteId": "%s",
                          "quantity": 300, "reason": "Part destinée à la fabrication" }
                        """.formatted(merchandise, rawMaterial, siteId))
                .when().post("/api/v1/stocks/reclassify")
                .then().statusCode(201)
                // Le coût suit la matière : 1500 de part et d'autre.
                .body("data.sourceAfter.quantity", equalTo(700))
                .body("data.destinationAfter.quantity", equalTo(300))
                .body("data.destinationAfter.cmup", equalTo(1500.0F));

        // La charge quitte 601 (marchandises) pour 602 (matières premières).
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("602000"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("601000"));
    }

    @Test
    void two_articles_of_the_same_nature_are_refused() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String first = createArticle(admin, "RAW_MATERIAL", "Cacao Méagui");
        String second = createArticle(admin, "RAW_MATERIAL", "Cacao Soubré");
        receive(admin, siteId, first, 100, 1000);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "fromArticleId": "%s", "toArticleId": "%s", "siteId": "%s", "quantity": 10 }
                        """.formatted(first, second, siteId))
                .when().post("/api/v1/stocks/reclassify")
                .then().statusCode(422)
                .body("statusMessage", containsString("transfert"));
    }

    @Test
    void a_merchandise_cannot_be_consumed_by_a_recipe() {
        UserEntity admin = tenantAdmin();
        String merchandise = createArticle(admin, "MERCHANDISE", "Cacao acheté pour revente");
        String finished = createArticle(admin, "FINISHED_PRODUCT", "Tablette 70%");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "Tablette", "finishedProductId": "%s",
                          "yieldQty": 1, "yieldUnit": "kg",
                          "ingredients": [ { "articleId": "%s", "quantity": 2, "unit": "kg" } ] }
                        """.formatted(finished, merchandise))
                .when().post("/api/v1/recipes")
                .then().statusCode(422)
                .body("statusMessage", containsString("requalifiez"));
    }
}
