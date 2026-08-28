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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Le barème d'une campagne est verrouillé.
 *
 * <p>Le prix bord champ est ce que la structure paie au producteur. Tant
 * qu'il vivait dans le même formulaire que le libellé, n'importe qui
 * pouvant écrire un référentiel pouvait le déplacer en corrigeant une
 * date, sans trace et sans motif. Un changement discret entre deux pesées
 * ne laissait rien derrière lui.</p>
 *
 * <p>Trois règles le ferment. Le geste ordinaire refuse de toucher au
 * barème. Le geste dédié exige un droit distinct, que la structure confie
 * à qui elle décide, direction ou conseil. Et chaque changement garde le
 * barème d'avant, celui d'après, son motif et son auteur.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignTariffLockTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-bareme-" + TestFixtures.randomSlugSuffix(), "Coopérative Barème");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user(Roles.TENANT_ADMIN, "admin");
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Test";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private String createRole(UserEntity admin, String name, String... permissions) {
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(name, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
    }

    private void assign(UserEntity admin, UserEntity target, String roleId) {
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + target.id)
                .then().statusCode(204);
    }

    private String createCampaign(UserEntity admin, int basePrice) {
        LocalDate today = LocalDate.now();
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne principale", "startDate": "%s", "endDate": "%s",
                          "basePricePerKgFcfa": %d }
                        """.formatted(today.minusMonths(1), today.plusMonths(5), basePrice))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    /** Payload de mise à jour ordinaire, barème inclus tel qu'il est envoyé par le formulaire. */
    private static String updateBody(String label, int basePrice) {
        LocalDate today = LocalDate.now();
        return """
                { "label": "%s", "startDate": "%s", "endDate": "%s",
                  "basePricePerKgFcfa": %d }
                """.formatted(label, today.minusMonths(1), today.plusMonths(5), basePrice);
    }

    private static void assertAmount(io.restassured.path.json.JsonPath json, String path, String expected) {
        org.junit.jupiter.api.Assertions.assertEquals(
                0, new BigDecimal(expected).compareTo(new BigDecimal(json.getString(path))),
                path + " attendu " + expected + ", obtenu " + json.getString(path));
    }

    @Test
    void the_ordinary_update_refuses_to_move_the_price() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);

        // Corriger le libellé sans toucher au prix reste possible.
        givenAs(a).contentType("application/json").body(updateBody("Campagne principale 2026", 900))
                .when().put("/api/v1/campaigns/" + id)
                .then().statusCode(200)
                .body("data.label", equalTo("Campagne principale 2026"));

        // Glisser un nouveau prix dans le même formulaire est refusé, même
        // pour l'administrateur : ce n'est pas une question de droit mais
        // de geste, le changement devant porter un motif.
        givenAs(a).contentType("application/json").body(updateBody("Campagne principale 2026", 950))
                .when().put("/api/v1/campaigns/" + id)
                .then().statusCode(422);

        givenAs(a).when().get("/api/v1/campaigns/" + id)
                .then().statusCode(200)
                .body("data.basePricePerKgFcfa", equalTo(900));
    }

    @Test
    void the_dedicated_change_keeps_the_previous_price_its_reason_and_its_author() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);

        var after = givenAs(a).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 950,
                          "reason": "Relèvement du prix bord champ décidé en conseil du 28 août." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(200)
                .body("data.tariffHistory", hasSize(1))
                .extract().jsonPath();

        assertAmount(after, "data.basePricePerKgFcfa", "950");
        assertAmount(after, "data.tariffHistory[0].previousBasePricePerKgFcfa", "900");
        assertAmount(after, "data.tariffHistory[0].newBasePricePerKgFcfa", "950");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Relèvement du prix bord champ décidé en conseil du 28 août.",
                after.getString("data.tariffHistory[0].reason"));
        org.junit.jupiter.api.Assertions.assertEquals(
                a.email, after.getString("data.tariffHistory[0].changedByEmail"));
    }

    @Test
    void a_change_without_a_reason_is_refused() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);

        givenAs(a).contentType("application/json")
                .body("{ \"basePricePerKgFcfa\": 950 }")
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(400);

        // Un motif d'un mot ne motive rien : la longueur minimale le refuse.
        givenAs(a).contentType("application/json")
                .body("{ \"basePricePerKgFcfa\": 950, \"reason\": \"ok\" }")
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(400);

        givenAs(a).when().get("/api/v1/campaigns/" + id)
                .then().body("data.basePricePerKgFcfa", equalTo(900));
    }

    @Test
    void writing_reference_data_does_not_grant_the_right_to_change_a_price() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);

        // Un profil qui gère les référentiels : il crée des articles, des
        // sections, des campagnes. Il ne fixe pas le prix payé.
        UserEntity clerk = user(Roles.USER, "gestionnaire");
        assign(a, clerk, createRole(a, "Gestionnaire référentiels",
                "REFERENTIAL_READ", "REFERENTIAL_WRITE"));

        givenAs(clerk).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 950, "reason": "Ajustement de ma propre initiative." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(403);

        // Le droit dédié, confié à qui la structure décide, ouvre le geste.
        UserEntity director = user(Roles.USER, "direction");
        assign(a, director, createRole(a, "Direction",
                "REFERENTIAL_READ", "CAMPAIGN_PRICE_WRITE"));

        givenAs(director).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 950,
                          "reason": "Alignement sur le prix garanti annoncé par la filière." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(200)
                .body("data.basePricePerKgFcfa", equalTo(950));
    }

    @Test
    void a_quality_premium_cannot_be_used_as_a_side_door() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);
        LocalDate today = LocalDate.now();

        // Le prix de base ne bouge pas, la prime qualité si : c'est autant
        // d'argent déplacé. Le geste ordinaire doit le refuser aussi.
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne principale", "startDate": "%s", "endDate": "%s",
                          "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "GR1", "premiumPerKg": 75 } ] }
                        """.formatted(today.minusMonths(1), today.plusMonths(5)))
                .when().put("/api/v1/campaigns/" + id)
                .then().statusCode(422);

        // Par le geste dédié, la prime change et l'historique la garde.
        var after = givenAs(a).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "GR1", "premiumPerKg": 75 } ],
                          "reason": "Prime GR1 votée pour encourager le séchage soigné." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(200)
                .body("data.tariffHistory", hasSize(1))
                .body("data.tariffHistory[0].previousQualityPremiums", hasSize(0))
                .body("data.tariffHistory[0].newQualityPremiums", hasSize(1))
                .extract().jsonPath();
        assertAmount(after, "data.tariffHistory[0].newQualityPremiums[0].premiumPerKg", "75");
    }

    @Test
    void resubmitting_the_same_price_is_not_a_change() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);

        // 900 et 900.00 sont un seul prix : un renvoi à l'identique ne doit
        // pas remplir l'historique de décisions qui n'en sont pas.
        givenAs(a).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 900.00, "reason": "Confirmation du prix en vigueur." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(422);

        givenAs(a).when().get("/api/v1/campaigns/" + id)
                .then().body("data.tariffHistory", hasSize(0));
    }

    @Test
    void a_closed_campaign_keeps_its_price() {
        UserEntity a = admin();
        String id = createCampaign(a, 900);
        givenAs(a).contentType("application/json")
                .when().post("/api/v1/campaigns/" + id + "/close").then().statusCode(200);

        // Rouvrir un barème clos réécrirait ce sur quoi une campagne a été
        // soldée : les états ne tiendraient plus.
        givenAs(a).contentType("application/json")
                .body("""
                        { "basePricePerKgFcfa": 950, "reason": "Correction après clôture." }
                        """)
                .when().put("/api/v1/campaigns/" + id + "/tariff")
                .then().statusCode(422);
    }
}
