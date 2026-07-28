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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Avances aux délégués collecteurs (backlog ACH-02) : versement d'avance,
 * livraisons de matière imputées sur l'avance jusqu'au solde, garde du
 * dépassement, clôture. Écritures 4091/trésorerie et 601/4091.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectorAdvanceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-av-" + TestFixtures.randomSlugSuffix(), "Coopérative Avances");
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

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
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

    private String createDelegate(UserEntity admin, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true,\"sectionId\":\"" + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void section_referential_crud() {
        UserEntity admin = tenantAdmin();
        createSection(admin, "MEAGUI", "Section Méagui");
        givenAs(admin).when().get("/api/v1/sections")
                .then().statusCode(200)
                .body("data.find { it.code == 'MEAGUI' }.name", equalTo("Section Méagui"));
    }

    @Test
    void advance_deliveries_and_balance() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "SOUBRE", "Section Soubré");
        String delegateId = createDelegate(admin, "Délégué Kouassi", sectionId);
        String articleId = createArticle(admin, "Cacao marchand");
        String siteId = createSite(admin);

        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 1000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201)
                .body("data.status", equalTo("OPEN"))
                .body("data.remainingFcfa", equalTo(1000000))
                .body("data.sectionName", equalTo("Section Soubré"))
                .extract().path("data.id");

        // Pièce d'avance au journal (4091 / trésorerie).
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(1));

        // Livraison de 500 kg à 1500 = 750 000, imputée sur l'avance.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "date": "%s", "quantity": 500, "unitPriceFcfa": 1500 }
                        """.formatted(articleId, LocalDate.now()))
                .when().post("/api/v1/collector-advances/" + advanceId + "/deliveries")
                .then().statusCode(200)
                .body("data.consumedAmountFcfa", equalTo(750000))
                .body("data.remainingFcfa", equalTo(250000));

        // Le stock a été crédité au coût bord champ.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(2));

        // Livraison qui dépasse le solde restant : refusée.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "date": "%s", "quantity": 200, "unitPriceFcfa": 1500 }
                        """.formatted(articleId, LocalDate.now()))
                .when().post("/api/v1/collector-advances/" + advanceId + "/deliveries")
                .then().statusCode(422);

        // Clôture avec solde résiduel.
        givenAs(admin).contentType("application/json").body("{\"note\":\"Fin de campagne\"}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/close")
                .then().statusCode(200).body("data.status", equalTo("CLOSED"));

        // Plus de livraison après clôture.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "date": "%s", "quantity": 10, "unitPriceFcfa": 1500 }
                        """.formatted(articleId, LocalDate.now()))
                .when().post("/api/v1/collector-advances/" + advanceId + "/deliveries")
                .then().statusCode(422);
    }

    @Test
    void advance_requires_a_collector_supplier() {
        UserEntity admin = tenantAdmin();
        // Fournisseur ordinaire (pas délégué).
        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Fournisseur ordinaire\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 100000, "paymentMethod": "CASH" }
                        """.formatted(supplierId, LocalDate.now()))
                .when().post("/api/v1/collector-advances")
                .then().statusCode(422);
    }
}
