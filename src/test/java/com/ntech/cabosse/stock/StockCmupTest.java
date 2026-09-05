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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Valorisation permanente au coût moyen unitaire pondéré, par
 * (article, site) : recalcul à chaque entrée, sorties valorisées au
 * CMUP courant sans le modifier, refus atomique du stock négatif.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockCmupTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cmup-" + TestFixtures.randomSlugSuffix(), "Coopérative CMUP");
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
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin, String name) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private void opening(UserEntity admin, String siteId, String articleId,
                         int qty, int unitPrice) {
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "siteId": "%s",
                          "lines": [ { "articleId": "%s", "quantity": %d, "unitPrice": %d } ] }
                        """.formatted(siteId, articleId, qty, unitPrice))
                .when().post("/api/v1/stocks/opening")
                .then().statusCode(201);
    }

    @Test
    void cmup_recalcule_a_chaque_entree_et_stable_en_sortie() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Fèves séchées");
        String siteId = createSite(admin, "Entrepôt Méagui");

        // Amorçage : 100 kg à 500 → CMUP 500.
        opening(admin, siteId, articleId, 100, 500);
        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.quantity", equalTo(100))
                .body("data.cmup", equalTo(500.0F));

        // Entrée : 50 kg à 800 → CMUP pondéré (100×500 + 50×800)/150 = 600.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "IN",
                          "quantity": 50, "unitPrice": 800 }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(201);
        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.quantity", equalTo(150))
                .body("data.cmup", equalTo(600.0F));

        // Sortie : 30 kg → quantité 120, CMUP inchangé.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "OUT", "quantity": 30 }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(201);
        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.quantity", equalTo(120))
                .body("data.cmup", equalTo(600.0F));
    }

    @Test
    void sortie_superieure_au_stock_est_refusee() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Beurre de karité");
        String siteId = createSite(admin, "Entrepôt Soubré");
        opening(admin, siteId, articleId, 10, 1000);

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "OUT", "quantity": 11 }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(422);

        // La quantité n'a pas bougé.
        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.quantity", equalTo(10));
    }

    @Test
    void transfert_conserve_la_valeur_entre_les_sites() {
        UserEntity admin = tenantAdmin();
        // Le plan « starter » du fixture limite les sites : on passe le
        // tenant sur un plan plus large avant de créer les deux entrepôts.
        TenantEntity tenant = tenants.findById(admin.tenantId);
        tenant.planCode = "pro";
        tenants.update(tenant);

        String articleId = createArticle(admin, "Cacao marchand");
        String from = createSite(admin, "Site source");
        String to = createSite(admin, "Site destination");
        opening(admin, from, articleId, 80, 750);

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "fromSiteId": "%s", "toSiteId": "%s", "quantity": 30 }
                        """.formatted(articleId, from, to))
                .when().post("/api/v1/stocks/transfer")
                .then().statusCode(201);

        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + from)
                .then().statusCode(200)
                .body("data.quantity", equalTo(50))
                .body("data.cmup", equalTo(750.0F));
        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + to)
                .then().statusCode(200)
                .body("data.quantity", equalTo(30))
                .body("data.cmup", equalTo(750.0F));
    }
}
