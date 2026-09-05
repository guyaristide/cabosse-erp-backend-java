package com.ntech.cabosse.campaign;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * L'axe campagne sur les opérations qui ne le portaient pas.
 *
 * <p>Sans lui, on pouvait sortir la collecte d'une campagne mais pas son
 * compte d'exploitation : les charges, les sorties de stock et les
 * écritures ne savaient pas de quelle campagne elles relevaient.</p>
 *
 * <p>On vérifie les deux points de passage — l'écriture comptable et le
 * mouvement de stock — parce que tout le reste y aboutit, et que le
 * tableau de bord sait désormais lire une campagne comme une période.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignAxisOnOperationsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        return adminOf(activeCocoaTenant("coop-axe-"));
    }

    private TenantEntity activeCocoaTenant(String slugPrefix) {
        TenantEntity tenant = fixtures.createActiveTenant(
                slugPrefix + TestFixtures.randomSlugSuffix(), "Coopérative Axe");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);
        return tenant;
    }

    private UserEntity adminOf(TenantEntity tenant) {
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

    private String createCampaign(UserEntity admin, String label, LocalDate start, LocalDate end) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 1500 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void a_stock_entry_carries_the_campaign_of_its_effective_date() {
        TenantEntity tenant = activeCocoaTenant("coop-stock-");
        UserEntity admin = adminOf(tenant);
        LocalDate today = LocalDate.now();
        String courante = createCampaign(admin, "Intermédiaire",
                today.minusMonths(3), today.plusMonths(2));

        String articleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Fèves de cacao", "unit": "kg" }
                        """)
                .when().post("/api/v1/articles")
                .then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites")
                .then().statusCode(201).extract().path("data.id");

        // Amorçage de stock : un mouvement d'entrée, daté d'aujourd'hui.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "lines": [
                            { "articleId": "%s", "quantity": 100, "unitPrice": 1200 } ] }
                        """.formatted(siteId, articleId))
                .when().post("/api/v1/stocks/opening")
                .then().statusCode(201);

        // Le mouvement porte la campagne, sans que personne ne l'ait saisie.
        org.bson.Document movement = mongoClient.getDatabase(tenant.databaseName)
                .getCollection("stock_movements")
                .find(new org.bson.Document("articleId", java.util.UUID.fromString(articleId)))
                .first();
        assertThat(movement).isNotNull();
        assertThat(movement.get("campaignId")).hasToString(courante);
        assertThat(movement.getInteger("campaignYear")).isNotNull();
    }

    @Test
    void the_dashboard_reads_a_campaign_as_a_period() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        createCampaign(admin, "Principale", today.minusMonths(10), today.minusMonths(4));
        createCampaign(admin, "Intermédiaire", today.minusMonths(3), today.plusMonths(2));

        // La campagne est une période à part entière, à côté du mois et de
        // l'année : le code renvoyé le confirme.
        givenAs(admin).when().get("/api/v1/executive-dashboard?period=campaign")
                .then().statusCode(200)
                .body("data.period", equalTo("campaign"));

        // Sans campagne au référentiel, on retombe sur le mois plutôt que
        // d'afficher des indicateurs vides sans dire pourquoi.
        UserEntity autre = tenantAdmin();
        givenAs(autre).when().get("/api/v1/executive-dashboard?period=campaign")
                .then().statusCode(200)
                .body("data.period", equalTo("month"));
    }

    @Test
    void the_legacy_french_period_codes_are_still_accepted() {
        UserEntity admin = tenantAdmin();
        // Un signet ou un onglet ouvert porte encore l'ancien code.
        givenAs(admin).when().get("/api/v1/executive-dashboard?period=mois")
                .then().statusCode(200)
                .body("data.period", equalTo("month"));
        givenAs(admin).when().get("/api/v1/executive-dashboard?period=annee")
                .then().statusCode(200)
                .body("data.period", equalTo("year"));
    }

    @Test
    void the_journal_can_be_filtered_by_campaign() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String courante = createCampaign(admin, "Intermédiaire",
                today.minusMonths(3), today.plusMonths(2));

        // Un filtre sur une campagne sans écriture ne renvoie rien, et le
        // filtre est repris dans l'enveloppe : c'est ce qui permet à
        // l'écran de savoir sur quoi il regarde.
        int total = givenAs(admin)
                .when().get("/api/v1/accounting/journal?campaignId=" + courante)
                .then().statusCode(200)
                .extract().path("data.total");
        assertThat(total).isZero();
    }
}
