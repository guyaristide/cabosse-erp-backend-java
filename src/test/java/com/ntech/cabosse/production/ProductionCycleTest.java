package com.ntech.cabosse.production;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace laissée par un cycle de production dans le journal des stocks.
 *
 * <p>Le test du cycle vérifie déjà les quantités et le CMUP du produit
 * fini. Ce qui restait sans filet : la piste que la production écrit dans
 * le journal des mouvements. C'est elle qui alimente la traçabilité par
 * lot et la valorisation comptable des sorties ; si la source, le prix
 * unitaire ou l'étiquette de lot cessent d'être posés, aucun écran ne le
 * montre, et c'est l'audit de traçabilité qui le découvre des mois plus
 * tard.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProductionCycleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity admin;
    private String siteId;
    private String cacaoId;
    private String chocolatId;
    private String recipeId;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cycle-" + TestFixtures.randomSlugSuffix(), "Coopérative Cycle");
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

    /** Atelier prêt : site, 500 kg de fèves à 1 200, recette 12 kg pour 10 kg. */
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
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "IN", "quantity": 500,
                          "unitPriceFcfa": 1200 }
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

    private List<Map<String, Object>> movementsOf(String articleId) {
        return givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId + "/movements")
                .then().statusCode(200)
                .extract().jsonPath().getList("data");
    }

    private static double asDouble(Object v) {
        return Double.parseDouble(v.toString());
    }

    @Test
    void le_journal_des_stocks_raconte_la_production_de_bout_en_bout() {
        workshop();

        JsonPath created = givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "recipeId": "%s", "plannedQty": 10 }
                        """.formatted(siteId, recipeId))
                .when().post("/api/v1/production-orders").then().statusCode(201)
                .extract().jsonPath();
        String orderId = created.getString("data.id");
        String lotRef = created.getString("data.lotRef");
        assertNotNull(lotRef, "L'ordre doit recevoir son étiquette de lot dès la création : "
                + "c'est elle que la traçabilité suivra");

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("{\"producedQty\":10}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(200);

        // Sortie matière : la consommation doit être marquée PRODUCTION et
        // valorisée au CMUP de la matière, sans quoi le coût de revient du
        // produit fini ne se recoupe plus avec le journal.
        Map<String, Object> out = movementsOf(cacaoId).stream()
                .filter(m -> "OUT".equals(m.get("kind")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "La consommation matière doit laisser un mouvement OUT au journal"));
        assertEquals("PRODUCTION", out.get("sourceType"),
                "La sortie matière doit être imputable à la production, pas anonyme");
        assertEquals(1200.0, asDouble(out.get("unitPriceFcfa")), 0.001,
                "La sortie matière se valorise au CMUP de la matière");
        assertEquals(12.0, Math.abs(asDouble(out.get("quantitySigned"))), 0.001);

        // Entrée produit fini : le lot de l'OF est reporté sur le mouvement.
        // C'est le maillon qui permet de remonter d'un lot vendu à sa
        // fabrication.
        Map<String, Object> in = movementsOf(chocolatId).stream()
                .filter(m -> "IN".equals(m.get("kind")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Le produit fini doit entrer en stock par un mouvement IN"));
        assertEquals("PRODUCTION", in.get("sourceType"));
        assertEquals(lotRef, in.get("lotRef"),
                "Le mouvement d'entrée doit porter le lot de l'ordre de fabrication");
        // 12 kg à 1 200 pour 10 kg produits : 1 440 le kilo.
        assertEquals(1440.0, asDouble(in.get("unitPriceFcfa")), 0.001,
                "Le produit fini entre au coût matière réellement consommé");
        assertEquals(1440.0, asDouble(in.get("cmupAfterFcfa")), 0.001);
    }

    @Test
    void une_production_ne_demarre_pas_sans_matiere_suffisante() {
        workshop();
        // 50 OF de 10 kg exigeraient 600 kg de fèves : il n'y en a que 500.
        String orderId = givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "recipeId": "%s", "plannedQty": 500 }
                        """.formatted(siteId, recipeId))
                .when().post("/api/v1/production-orders").then().statusCode(201)
                .extract().path("data.id");

        // 500 kg produits exigent 600 kg de matière : le démarrage doit
        // refuser, sinon le stock passerait négatif en silence.
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(422);

        // Et rien ne doit être sorti du stock par la tentative refusée.
        long outs = movementsOf(cacaoId).stream()
                .filter(m -> "OUT".equals(m.get("kind"))).count();
        assertEquals(0, outs, "Un démarrage refusé ne doit laisser aucune sortie au journal");
    }

    @Test
    void la_surproduction_reste_valorisee_par_la_matiere_reellement_sortie() {
        workshop();
        String orderId = givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "recipeId": "%s", "plannedQty": 10 }
                        """.formatted(siteId, recipeId))
                .when().post("/api/v1/production-orders").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/production-orders/" + orderId + "/start")
                .then().statusCode(200);

        // Le rendement réel dépasse le plan : 12 kg de matière ont produit
        // 12 kg au lieu de 10. Le coût unitaire doit refléter la matière
        // sortie (14 400 / 12 = 1 200), pas le plan.
        givenAs(admin).contentType("application/json")
                .body("{\"producedQty\":12}")
                .when().post("/api/v1/production-orders/" + orderId + "/complete")
                .then().statusCode(200);

        Map<String, Object> in = movementsOf(chocolatId).stream()
                .filter(m -> "IN".equals(m.get("kind")))
                .findFirst().orElseThrow();
        assertEquals(12.0, asDouble(in.get("quantitySigned")), 0.001);
        assertEquals(1200.0, asDouble(in.get("unitPriceFcfa")), 0.001,
                "Produire plus que prévu ne crée pas de valeur : le coût suit la matière");
        assertTrue(asDouble(in.get("totalFcfa")) > 0);
    }
}
