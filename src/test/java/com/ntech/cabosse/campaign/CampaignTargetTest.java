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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Les objectifs mensuels d'une campagne.
 *
 * <p>Demandés par la coopérative le 13/09/2026 : ses tableaux de bord
 * confrontent chaque mois le réalisé à un objectif et affichent l'écart.
 * Le réalisé se calcule déjà ; l'objectif est une décision, il ne se
 * déduit de rien.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignTargetTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-obj-" + TestFixtures.randomSlugSuffix(), "Coopérative Objectifs");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Objectifs";
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

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Campagne";
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

    private String campaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKg": 1200 }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse setTarget(UserEntity who, String campaignId,
                                                                   String body) {
        return givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/campaigns/" + campaignId + "/targets").then();
    }

    @Test
    void a_target_is_set_corrected_and_cleared() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();

        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 12000, \"saleTargetKg\": 9000 }"
                        .formatted(month))
                .statusCode(200)
                .body("data.collectionTargetKg", equalTo(12000));

        // Une seconde saisie corrige la première : deux objectifs sur le
        // même mois ne voudraient plus rien dire.
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 15000 }".formatted(month))
                .statusCode(200);
        givenAs(admin).when().get("/api/v1/campaigns/" + campaignId + "/targets")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].collectionTargetKg", equalTo(15000))
                .body("data[0].saleTargetKg", nullValue());

        // Les deux montants absents effacent l'objectif : sans cela, un
        // mois vidé garderait une cible fantôme contre laquelle l'écart
        // continuerait de se calculer.
        setTarget(admin, campaignId, "{ \"month\": \"%s\" }".formatted(month)).statusCode(200);
        givenAs(admin).when().get("/api/v1/campaigns/" + campaignId + "/targets")
                .then().statusCode(200).body("data", hasSize(0));
    }

    /**
     * Le coût d'achat complet n'existe que si la structure a désigné les
     * comptes de frais qui le composent. Sans eux, un coût « complet »
     * égal au prix nu se lirait comme une absence de frais.
     */
    @Test
    void the_full_purchase_cost_stays_silent_until_the_fee_accounts_are_named() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);

        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.kpis.avgPurchaseCostPerKg", nullValue())
                .body("data.kpis.purchaseSideCosts", nullValue());

        givenAs(admin).contentType("application/json")
                .body("{ \"purchaseCostAccounts\": [\"624000\"] }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        // Déclarés, les frais existent : zéro tant que rien n'a été
        // enregistré dessus, ce qui est un chiffre, pas une absence.
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.kpis.purchaseSideCosts", equalTo(0));
    }

    /**
     * Les ventes se ventilent par label de certification, y compris
     * celles qui n'en portent pas : les écarter ferait mentir le total.
     */
    @Test
    void sales_split_by_label_and_nothing_is_dropped() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);

        // Le référentiel connaît « RA » : une vente saisie « ra » doit
        // s'y reconnaître, accents, casse et ponctuation ignorés.
        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"RA\",\"name\":\"Rainforest Alliance\"}")
                .when().post("/api/v1/certifications").then().statusCode(201);

        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                // Sans vente, aucune ligne : un label à zéro se lirait
                // comme une certification qui n'a rien vendu, alors que
                // c'est la campagne entière qui n'a rien vendu.
                .body("data.labels", hasSize(0));
    }

    /**
     * Le barème du conseil de filière se saisit composante par
     * composante, et le total se somme plutôt que de se saisir : deux
     * vérités sur le même chiffre finiraient par diverger.
     */
    @Test
    void the_collection_scale_sums_its_parts_and_stays_silent_until_entered() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);

        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.scale", nullValue());

        givenAs(admin).contentType("application/json")
                .body("{ \"transportPerKg\": 10, \"gatheringPerKg\": 60,"
                        + " \"buyerRemunerationPerKg\": 30 }")
                .when().put("/api/v1/campaigns/" + campaignId + "/collection-scale")
                .then().statusCode(200);

        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.scale.scaleTotalPerKg", equalTo(100))
                .body("data.scale.gatheringPerKg", equalTo(60));

        // Les trois composantes absentes effacent le barème : en garder
        // un à zéro ferait comparer le réalisé à rien, en dépassement
        // permanent.
        givenAs(admin).contentType("application/json").body("{}")
                .when().put("/api/v1/campaigns/" + campaignId + "/collection-scale")
                .then().statusCode(200);
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.scale", nullValue());
    }

    /**
     * Le pilotage s'emporte : un comité qui prépare sa réunion ne doit
     * pas recopier les chiffres à la main (relevé le 13/09/2026).
     */
    @Test
    void the_campaign_steering_downloads_with_its_targets_and_gaps() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 10000 }".formatted(month))
                .statusCode(200);

        String csv = givenAs(admin)
                .queryParam("campaignId", campaignId).queryParam("format", "csv")
                .when().get("/api/v1/executive-dashboard/campaign/export")
                .then().statusCode(200).extract().asString();

        // L'objectif et l'écart voyagent avec le réalisé : un fichier qui
        // ne porterait que le réalisé ferait refaire la soustraction.
        org.assertj.core.api.Assertions.assertThat(csv)
                .contains(month)
                .contains("Objectif de collecte")
                .contains("Écart collecte");
        // Les nombres sortent formatés à la française, espace insécable
        // compris : c'est la règle du produit, on la neutralise pour
        // comparer la valeur plutôt que sa présentation.
        String plain = csv.replaceAll("[\\s\\u00A0\\u202F]", "");
        org.assertj.core.api.Assertions.assertThat(plain).contains("10000");
    }

    /**
     * Deux lectures du pilotage de campagne. Le responsable de collecte
     * a besoin des tonnages, des objectifs et des écarts ; il n'a pas à
     * connaître le chiffre d'affaires, les marges ni la trésorerie.
     */
    @Test
    void the_steering_reads_without_amounts_for_whoever_lacks_the_executive_right() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 10000 }".formatted(month))
                .statusCode(200);

        UserEntity agent = user("collecte", Roles.USER);
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Responsable collecte\","
                        + " \"permissions\": [\"CAMPAIGN_STEERING_READ\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + agent.id).then().statusCode(204);

        var response = givenAs(agent).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200);

        // Il voit ce qui sert à son travail.
        response.body("data.financialsVisible", equalTo(false))
                .body("data.kpis.purchasedWeight", notNullValue())
                .body("data.months.find { it.month == '%s' }.collectionTargetKg".formatted(month),
                        equalTo(10000));

        // Et pas ce qui relève de l'argent. Absent, jamais à zéro : des
        // cases vides feraient croire à une campagne sans chiffre
        // d'affaires plutôt qu'à un droit manquant.
        response.body("data.kpis.revenue", nullValue())
                .body("data.kpis.grossMargin", nullValue())
                .body("data.kpis.avgSalePricePerKg", nullValue())
                .body("data.months.find { it.month == '%s' }.revenue".formatted(month),
                        nullValue())
                // Les avances aux délégués ne se vident pas, elles
                // s'effacent : une liste de noms suivie de zéros dirait
                // que personne ne doit rien.
                .body("data.delegates", hasSize(0))
                .body("data.scale", nullValue());

        // L'administrateur, lui, voit tout.
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.financialsVisible", equalTo(true))
                .body("data.kpis.revenue", notNullValue());
    }

    @Test
    void a_month_outside_the_campaign_is_refused() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);

        // Un objectif posé hors campagne ne serait confronté à rien : le
        // tableau de bord ne parcourt que les mois de la campagne, et la
        // cible resterait invisible sans qu'on comprenne pourquoi.
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 500 }"
                        .formatted(YearMonth.now().plusYears(3)))
                .statusCode(422);
    }

    @Test
    void the_dashboard_shows_the_gap_only_where_a_target_was_set() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();

        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 10000 }".formatted(month))
                .statusCode(200);

        var response = givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200);

        // Rien n'a été collecté : l'écart vaut le retard entier, et il
        // n'existe que parce qu'un objectif a été posé.
        response.body("data.months.find { it.month == '%s' }.collectionTargetKg".formatted(month),
                        equalTo(10000))
                .body("data.months.find { it.month == '%s' }.collectionGapKg".formatted(month),
                        equalTo(-10000))
                // Aucun objectif de vente : ni cible, ni écart. Un zéro se
                // lirait comme une cible décidée à zéro.
                .body("data.months.find { it.month == '%s' }.saleTargetKg".formatted(month),
                        nullValue())
                .body("data.months.find { it.month == '%s' }.saleGapKg".formatted(month),
                        nullValue());
    }
}
