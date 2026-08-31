package com.ntech.cabosse.collector;

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
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * La mise en compte du délégué : ce que la coopérative lui retient, par
 * kilo livré.
 *
 * <p>À ne pas confondre avec la marge de fonctionnement, qui est ce
 * qu'elle lui verse. Les deux se négocient délégué par délégué, et la mise
 * en compte devient <strong>obligatoire</strong> dès qu'il traîne une
 * dette d'une campagne antérieure : c'est la contrepartie du
 * refinancement.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateRetentionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-mec-" + TestFixtures.randomSlugSuffix(), "Coopérative Mise en compte");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);

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
        // Une caisse ne peut jamais être négative : la structure y met
        // son solde d'ouverture avant toute sortie d'espèces.
        fundCashBox(u, 50_000_000);
        return u;
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createSection(UserEntity admin) {
        String code = "SEC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"Section test\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    /** @param retentionPerKg mise en compte convenue, null si aucune */
    private String createDelegate(UserEntity admin, String sectionId, Integer retentionPerKg) {
        String code = "del-" + UUID.randomUUID().toString().substring(0, 6);
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "KONE Adama", "collector": true,
                          "sectionId": "%s"%s }
                        """.formatted(code, sectionId,
                        retentionPerKg == null ? "" : ", \"collectorRetentionPerKgFcfa\": " + retentionPerKg))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String createProducer(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kouassi\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
    }

    private String createCampaign(UserEntity admin, String label, LocalDate start, LocalDate end) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKgFcfa": 1000 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
    }

    private void openAdvance(UserEntity admin, String delegateId, String siteId,
                             String campaignId, int amount, LocalDate date, int expected) {
        var response = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CASH",
                          "campaignId": "%s" }
                        """.formatted(delegateId, date, amount, campaignId))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(expected);
        if (expected != 201) return;
        // Une avance n'est imputable qu'une fois décaissée.
        String id = response.extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
    }

    private void createReceipt(UserEntity admin, String memberId, String articleId, String siteId,
                               String delegateId, int weight, LocalDate date) {
        givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH", "delegateSupplierId": "%s" }
                        """.formatted(date, memberId, articleId, siteId, weight, delegateId))
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
    }

    /** Compare un montant JSON à sa valeur, quelle que soit son écriture. */
    private static void assertAmount(io.restassured.path.json.JsonPath json, String path, String expected) {
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                new java.math.BigDecimal(expected).compareTo(new java.math.BigDecimal(json.getString(path))),
                path + " attendu " + expected + ", obtenu " + json.getString(path));
    }

    @Test
    void the_retention_is_frozen_on_each_receipt_and_shows_in_the_account() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String sectionId = createSection(admin);
        // 25 FCFA/kg de mise en compte, dans la fourchette usuelle 10 à 35.
        String delegateId = createDelegate(admin, sectionId, 25);
        String memberId = createProducer(admin);

        openAdvance(admin, delegateId, siteId, campaign, 1_000_000, today, 201);
        createReceipt(admin, memberId, articleId, siteId, delegateId, 200, today);

        var account = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId
                        + "?campaignId=" + campaign)
                .then().statusCode(200)
                .body("data.repaymentRatePct", notNullValue())
                .extract().jsonPath();

        // Les montants voyagent tantôt en entier tantôt en décimal selon
        // leur échelle : on compare des nombres, pas leur écriture.
        assertAmount(account, "data.totalRetentionFcfa", "5000");   // 200 kg x 25 FCFA
        assertAmount(account, "data.totalWeightKg", "200");
        assertAmount(account, "data.averagePricePerKgFcfa", "1000"); // 200 000 / 200
        assertAmount(account, "data.netBalanceFcfa", "795000");      // 1 000 000 − (200 000 + 5 000)
    }

    /**
     * Le refinancement d'un délégué endetté est signalé, pas refusé.
     *
     * <p>C'était un blocage : la demande était rejetée tant qu'aucune mise
     * en compte n'était convenue. Décision de l'utilisateur du 30/08/2026,
     * énoncée comme une règle générale : le système constate et donne à
     * décider, il n'arbitre pas. Refinancer un délégué qui traîne une
     * dette sans contrepartie est un choix de gouvernance.</p>
     */
    @Test
    void a_delegate_with_prior_debt_is_flagged_not_blocked() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String past = createCampaign(admin, "Principale passée",
                today.minusMonths(10), today.minusMonths(5));
        String current = createCampaign(admin, "Intermédiaire",
                today.minusMonths(2), today.plusMonths(3));
        String siteId = createSite(admin);
        String sectionId = createSection(admin);
        // Aucune mise en compte convenue sur sa fiche.
        String delegateId = createDelegate(admin, sectionId, null);

        // Campagne passée : il reçoit 500 000 et ne livre rien.
        openAdvance(admin, delegateId, siteId, past, 500_000, today.minusMonths(8), 201);

        // Le refinancer sans contrepartie passe : le logiciel n'arbitre pas.
        openAdvance(admin, delegateId, siteId, current, 300_000, today, 201);

        // Mais la fiche technique le signale, pour que la décision se
        // prenne les yeux ouverts.
        givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + current)
                .then().statusCode(200)
                .body("data.hasPriorDebt", org.hamcrest.Matchers.is(true))
                .body("data.retentionMissingOnPriorDebt", org.hamcrest.Matchers.is(true));

        // Une fois la mise en compte convenue, l'avertissement tombe.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "KONE Adama", "collector": true, "sectionId": "%s",
                          "collectorRetentionPerKgFcfa": 20 }
                        """.formatted(sectionId))
                .when().put("/api/v1/suppliers/" + delegateId)
                .then().statusCode(200);
        openAdvance(admin, delegateId, siteId, current, 300_000, today, 201);

        givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + current)
                .then().statusCode(200)
                .body("data.retentionMissingOnPriorDebt", org.hamcrest.Matchers.is(false));
    }

    @Test
    void a_delegate_without_prior_debt_needs_no_retention() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        String siteId = createSite(admin);
        String sectionId = createSection(admin);
        String delegateId = createDelegate(admin, sectionId, null);

        // Première campagne, aucune dette : rien n'est exigé.
        openAdvance(admin, delegateId, siteId, campaign, 400_000, today, 201);
    }

    @Test
    void the_technical_record_computes_the_scale_price() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        String sectionId = createSection(admin);
        String delegateId = createDelegate(admin, sectionId, 15);

        // Marge de fonctionnement au kilo, réglée pour la structure.
        givenAs(admin).contentType("application/json")
                .body("{\"delegateMarginMode\":\"PER_KG\",\"delegateMarginRate\":50}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        var terms = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + campaign + "&volumeKg=1000")
                .then().statusCode(200)
                .body("data.hasPriorDebt", equalTo(false))
                .extract().jsonPath();

        assertAmount(terms, "data.basePricePerKgFcfa", "1000");
        assertAmount(terms, "data.marginPerKgFcfa", "50");
        assertAmount(terms, "data.retentionPerKgFcfa", "15");
        // Prix barème = prix bord champ + marge de fonctionnement
        assertAmount(terms, "data.scalePricePerKgFcfa", "1050");
        // Avance suggérée = prix barème x volume à livrer
        assertAmount(terms, "data.suggestedAdvanceFcfa", "1050000");
    }
}
