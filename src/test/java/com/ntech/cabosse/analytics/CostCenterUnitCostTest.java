package com.ntech.cabosse.analytics;

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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

/**
 * Coût unitaire par centre de coût (extension reporting CPT-17) : charges du
 * centre rapportées au volume d'activité (quantité achetée), et garde-fou
 * lorsqu'un centre agrège des unités hétérogènes.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CostCenterUnitCostTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cout-" + TestFixtures.randomSlugSuffix(), "Coopérative Coût unitaire");
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

    private void createCostCenter(UserEntity admin, String code, String name) {
        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/cost-centers").then().statusCode(201);
    }

    private String createArticle(UserEntity admin, String name, String unit, String costCenter) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"" + unit
                        + "\",\"defaultCostCenter\":\"" + costCenter + "\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createSupplier(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private void receive(UserEntity admin, String siteId, String articleId, String supplierId, int qty, int price) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "receivedDate": "%s",
                          "lines": [ { "supplierId": "%s", "quantity": %d, "unitPrice": %d } ] }
                        """.formatted(articleId, LocalDate.now(), supplierId, qty, price))
                .when().post("/api/v1/direct-receipts?siteId=" + siteId).then().statusCode(201);
    }

    @Test
    void unit_cost_is_charges_over_purchased_volume() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "COL", "Collecte");
        String articleId = createArticle(admin, "Cacao marchand", "kg", "COL");
        String supplierId = createSupplier(admin, "Producteur Kouassi");
        String siteId = createSite(admin);

        receive(admin, siteId, articleId, supplierId, 100, 1500); // charge 150 000 sur COL, 100 kg entrés

        JsonPath jp = givenAs(admin).when()
                .get("/api/v1/accounting/analytics/cost-centers?volumeBasis=PURCHASED")
                .then().statusCode(200).extract().jsonPath();

        Assertions.assertEquals(150000.0, jp.getDouble("data.find { it.code == 'COL' }.charges"), 0.01);
        Assertions.assertEquals(100.0, jp.getDouble("data.find { it.code == 'COL' }.volumeQuantity"), 0.01);
        Assertions.assertEquals("kg", jp.getString("data.find { it.code == 'COL' }.unit"));
        Assertions.assertEquals(1500.0, jp.getDouble("data.find { it.code == 'COL' }.unitCost"), 0.01);
        Assertions.assertEquals(false, jp.getBoolean("data.find { it.code == 'COL' }.mixedUnits"));
    }

    @Test
    void mixed_units_disable_unit_cost() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "COL", "Collecte");
        String supplierId = createSupplier(admin, "Producteur Bamba");
        String kgArticle = createArticle(admin, "Cacao", "kg", "COL");
        String pcsArticle = createArticle(admin, "Sacs", "pcs", "COL");
        String siteId = createSite(admin);

        receive(admin, siteId, kgArticle, supplierId, 100, 1500);
        receive(admin, siteId, pcsArticle, supplierId, 50, 200);

        JsonPath jp = givenAs(admin).when()
                .get("/api/v1/accounting/analytics/cost-centers")
                .then().statusCode(200).extract().jsonPath();

        // COL mélange kg et pcs : pas de coût unitaire, drapeau posé.
        Assertions.assertEquals(true, jp.getBoolean("data.find { it.code == 'COL' }.mixedUnits"));
        Assertions.assertNull(jp.get("data.find { it.code == 'COL' }.unitCost"));
        Assertions.assertNull(jp.get("data.find { it.code == 'COL' }.volumeQuantity"));
    }
}
