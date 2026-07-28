package com.ntech.cabosse.collector;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

/**
 * Valorisation d'une livraison délégué (backlog v21, 1er circuit Production),
 * paramétrable par tenant : mode « par lot » (défaut) où le coût de l'avance
 * fait autorité (le CMUP prend ce coût), et mode « CMUP pondéré » où la
 * livraison se fond dans la moyenne pondérée comme un achat classique.
 *
 * <p>Deux livraisons de même article/site sur la même avance, à 1000 puis
 * 1500 : par lot le CMUP final vaut 1500 (le dernier coût), pondéré il vaut
 * (100·1000 + 100·1500) / 200 = 1250.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectorValuationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-valo-" + TestFixtures.randomSlugSuffix(), "Coopérative Valorisation");
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

    private String createSection(UserEntity admin, String code, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true,\"sectionId\":\"" + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String openAdvance(UserEntity admin, String delegateId, String siteId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 400000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201).extract().path("data.id");
    }

    private void deliver(UserEntity admin, String advanceId, String articleId, int qty, int unitPrice) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "date": "%s", "quantity": %d, "unitPriceFcfa": %d }
                        """.formatted(articleId, LocalDate.now(), qty, unitPrice))
                .when().post("/api/v1/collector-advances/" + advanceId + "/deliveries")
                .then().statusCode(200);
    }

    private double cmup(UserEntity admin, String articleId, String siteId) {
        return givenAs(admin).when()
                .get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200).extract().jsonPath().getDouble("data.cmupFcfa");
    }

    @Test
    void by_lot_mode_lets_advance_cost_drive_cmup() {
        UserEntity admin = tenantAdmin(); // défaut = BY_LOT
        String sectionId = createSection(admin, "MEAGUI", "Section Méagui");
        String delegateId = createDelegate(admin, "Délégué A", sectionId);
        String articleId = createArticle(admin, "Cacao marchand");
        String siteId = createSite(admin);
        String advanceId = openAdvance(admin, delegateId, siteId);

        deliver(admin, advanceId, articleId, 100, 1000);
        deliver(admin, advanceId, articleId, 100, 1500);

        // Par lot : le dernier coût d'avance fait autorité.
        Assertions.assertEquals(1500.0, cmup(admin, articleId, siteId), 0.01);
    }

    @Test
    void weighted_mode_blends_advance_cost_into_cmup() {
        UserEntity admin = tenantAdmin();
        // Bascule sur le mode CMUP pondéré.
        givenAs(admin).contentType("application/json")
                .body("{\"collectorDeliveryValuation\":\"WEIGHTED_CMUP\"}")
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200);

        String sectionId = createSection(admin, "SOUBRE", "Section Soubré");
        String delegateId = createDelegate(admin, "Délégué B", sectionId);
        String articleId = createArticle(admin, "Cacao marchand");
        String siteId = createSite(admin);
        String advanceId = openAdvance(admin, delegateId, siteId);

        deliver(admin, advanceId, articleId, 100, 1000);
        deliver(admin, advanceId, articleId, 100, 1500);

        // Pondéré : (100·1000 + 100·1500) / 200 = 1250.
        Assertions.assertEquals(1250.0, cmup(admin, articleId, siteId), 0.01);
    }
}
