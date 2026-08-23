package com.ntech.cabosse.direction;

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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tableau de bord de direction : les chiffres que la direction regarde
 * doivent venir des opérations réelles.
 *
 * <p>C'est l'écran sur lequel se prennent les décisions de campagne. Un
 * chiffre d'affaires qui ne remonte plus les ventes, ou une alerte de
 * stock bas qui ne se déclenche plus, ne casse aucun autre écran : seul
 * ce test le voit avant la direction.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ExecutiveDashboardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity admin;
    private String siteId;

    private void setUpTenant() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dir-" + TestFixtures.randomSlugSuffix(), "Coopérative Direction");
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
        admin = u;

        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private JsonPath dashboard() {
        return givenAs(admin)
                .when().get("/api/v1/executive-dashboard?period=mois")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static Map<String, Object> kpi(JsonPath body, String key) {
        List<Map<String, Object>> kpis = body.getList("data.kpis");
        return kpis.stream().filter(k -> key.equals(k.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "L'indicateur « " + key + " » doit toujours figurer au tableau de bord"));
    }

    @Test
    void le_chiffre_d_affaires_remonte_les_ventes_du_mois() {
        setUpTenant();
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Client Export\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"FINISHED_PRODUCT\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        // Une vente confirmée de 3 000 000 : c'est elle, et rien d'autre,
        // qui doit faire le chiffre d'affaires du mois.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 1500, "unitPriceFcfa": 2000 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=false")
                .then().statusCode(201);

        Map<String, Object> revenue = kpi(dashboard(), "revenue");
        assertEquals(3000000.0, Double.parseDouble(revenue.get("current").toString()), 0.001,
                "Le chiffre d'affaires du tableau de bord doit refléter les ventes confirmées");
    }

    @Test
    void un_article_sous_son_seuil_declenche_l_alerte_stock_bas() {
        setUpTenant();
        // Seuil à 50, stock à 10 : la direction doit le voir sans ouvrir
        // l'écran des stocks.
        String articleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Fèves séchées", "unit": "kg",
                          "alertThreshold": 50 }
                        """)
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "IN", "quantity": 10,
                          "unitPriceFcfa": 500 }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements").then().statusCode(201);

        List<Map<String, Object>> alerts = dashboard().getList("data.alerts");
        assertTrue(alerts.stream().anyMatch(a ->
                        String.valueOf(a.get("title")).contains("sous seuil")),
                "Un article sous son seuil doit lever l'alerte de stock bas, trouvé : " + alerts);
    }

    @Test
    void le_tableau_de_bord_tient_sur_un_tenant_vierge() {
        setUpTenant();
        // Premier jour d'un client : quatre indicateurs servis, à zéro,
        // sans erreur. Un tableau de bord qui tombe à vide condamne la
        // première impression.
        JsonPath body = dashboard();
        for (String key : List.of("revenue", "margin", "cash", "stockValue")) {
            Map<String, Object> k = kpi(body, key);
            assertNotNull(k.get("current"), "L'indicateur « " + key + " » doit être servi même à vide");
        }
    }
}
