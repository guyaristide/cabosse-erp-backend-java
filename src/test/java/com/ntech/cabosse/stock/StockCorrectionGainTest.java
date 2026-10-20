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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Une correction de stock peut aussi ajouter du poids.
 *
 * <p>Le soleil fait perdre du poids, l'humidité en fait gagner : « ils
 * ont eu ce cas hier » (demandé le 20/10/2026). Stock avant, moins la
 * perte, plus le gain, chacun au coût moyen du jour, qui ne bouge pas.
 * Pas de sacs ajoutés : on peut en retirer, jamais en ajouter.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockCorrectionGainTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-gain-" + TestFixtures.randomSlugSuffix(), "Coopérative Gain");
        // La fiche du jour suit la capacité du modèle coopératif.
        tenant.organizationModel = com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Gain";
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

    /** Un magasin, un article, mille kilos à 1 200 depuis hier. */
    private String[] stockedSite(UserEntity admin) {
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 1000, "unitPrice": 1200 } ] }
                        """.formatted(siteId,
                        Instant.now().minus(java.time.Duration.ofDays(1)), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);
        return new String[] { siteId, articleId };
    }

    private io.restassured.response.ValidatableResponse correct(UserEntity admin, String siteId,
                                                                String articleId, String body) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "date": "%s", %s }
                        """.formatted(articleId, siteId, LocalDate.now(), body))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stock-corrections").then();
    }

    @Test
    void a_gain_enters_the_stock_at_the_average_cost_without_moving_it() {
        UserEntity a = admin();
        String[] s = stockedSite(a);

        correct(a, s[0], s[1], "\"reason\": \"Soleil du 15 : gain de poids\", \"gainKg\": 40")
                .statusCode(201)
                .body("data.gainKg", equalTo(40))
                .body("data.gainValue", equalTo(48000.00F))
                .body("data.value", nullValue())
                .body("data.pieceRef", notNullValue());

        givenAs(a).when().get("/api/v1/stocks/" + s[1] + "/sites/" + s[0])
                .then().statusCode(200)
                .body("data.quantity", equalTo(1040))
                .body("data.cmup", equalTo(1200.0F));
    }

    @Test
    void a_loss_and_a_gain_can_share_one_correction_with_bags_removed_only() {
        UserEntity a = admin();
        String[] s = stockedSite(a);

        correct(a, s[0], s[1],
                "\"reason\": \"Brassage\", \"bags\": 2, \"weightKg\": 100, \"gainKg\": 40")
                .statusCode(201)
                .body("data.value", equalTo(120000.00F))
                .body("data.gainValue", equalTo(48000.00F));

        // 1000 - 100 + 40
        givenAs(a).when().get("/api/v1/stocks/" + s[1] + "/sites/" + s[0])
                .then().statusCode(200)
                .body("data.quantity", equalTo(940));

        // La fiche du jour lit un seul chiffre, net et signé.
        givenAs(a).queryParam("date", LocalDate.now().toString()).queryParam("siteId", s[0])
                .when().get("/api/v1/producer-purchases/day-sheet")
                .then().statusCode(200)
                .body("data.rows.find { it.rowKind == 'CORRECTION' }.weightKg", equalTo(-60))
                .body("data.rows.find { it.rowKind == 'CORRECTION' }.nbSacs", equalTo(-2));
    }

    @Test
    void a_correction_that_neither_removes_nor_adds_is_refused() {
        UserEntity a = admin();
        String[] s = stockedSite(a);
        correct(a, s[0], s[1], "\"reason\": \"Rien\", \"bags\": 1").statusCode(422);
    }
}
