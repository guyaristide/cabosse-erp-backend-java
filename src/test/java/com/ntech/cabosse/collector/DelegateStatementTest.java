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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Les états de suivi des délégués demandés par l'expert.
 *
 * <p>Quatre tableaux étaient attendus au-delà du compte courant : la mise
 * en compte par délégué, la marge par délégué, les deux ensemble, et le
 * détail daté portant le numéro de brousse. Les trois premiers ne diffèrent
 * que par les colonnes montrées : le serveur produit un relevé, l'écran en
 * choisit la lecture. C'est ce relevé qu'on éprouve ici, ainsi que le
 * cumul du détail daté.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-etat-" + TestFixtures.randomSlugSuffix(), "Coopérative États");
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
                .body("{\"code\":\"" + code + "\",\"name\":\"Section Bangolo\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String code, String name,
                                  String sectionId, Integer retentionPerKg) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "%s", "collector": true, "sectionId": "%s"%s }
                        """.formatted(code, name, sectionId,
                        retentionPerKg == null ? "" : ", \"collectorRetentionPerKg\": " + retentionPerKg))
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
                          "basePricePerKg": 1000 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
    }

    private void openAdvance(UserEntity admin, String delegateId, String siteId,
                             String campaignId, int amount, LocalDate date) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH", "campaignId": "%s" }
                        """.formatted(delegateId, date, amount, campaignId))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201).extract().path("data.id");
        // Une avance n'est imputable qu'une fois décaissée.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
    }

    private void createReceipt(UserEntity admin, String memberId, String articleId, String siteId,
                               String delegateId, int weight, LocalDate date, String deliveryRef) {
        givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKg": 1000,
                          "paymentMethod": "CASH", "delegateSupplierId": "%s"%s }
                        """.formatted(date, memberId, articleId, siteId, weight, delegateId,
                        deliveryRef == null ? "" : ", \"deliveryRef\": \"" + deliveryRef + "\""))
                .when().post("/api/v1/producer-purchases").then().statusCode(201);
    }

    private static void assertAmount(io.restassured.path.json.JsonPath json, String path, String expected) {
        org.junit.jupiter.api.Assertions.assertEquals(
                0, new BigDecimal(expected).compareTo(new BigDecimal(json.getString(path))),
                path + " attendu " + expected + ", obtenu " + json.getString(path));
    }

    @Test
    void the_statement_carries_both_rates_and_both_amounts_for_every_delegate() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String sectionId = createSection(admin);
        String memberId = createProducer(admin);

        String actif = createDelegate(admin, "del-a", "KONE Adama", sectionId, 25);
        // Un délégué sans mise en compte convenue et sans livraison : il
        // doit apparaître à zéro, pas disparaître du relevé.
        createDelegate(admin, "del-b", "TRAORE Salif", sectionId, null);

        createReceipt(admin, memberId, articleId, siteId, actif, 200, today, "BRO-001");

        var statement = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/statement?campaignId=" + campaign)
                .then().statusCode(200)
                .body("data.rows", hasSize(2))
                .extract().jsonPath();

        // Les lignes sont triées par code : del-a précède del-b.
        org.junit.jupiter.api.Assertions.assertEquals("del-a", statement.getString("data.rows[0].delegateCode"));
        assertAmount(statement, "data.rows[0].retentionPerKg", "25");
        assertAmount(statement, "data.rows[0].retentionAmount", "5000");  // 200 kg x 25
        assertAmount(statement, "data.rows[0].weightKg", "200");
        assertAmount(statement, "data.rows[0].delivered", "200000");

        org.junit.jupiter.api.Assertions.assertEquals("del-b", statement.getString("data.rows[1].delegateCode"));
        assertAmount(statement, "data.rows[1].retentionAmount", "0");

        // Les taux ne s'additionnent pas : seuls les montants sont totalisés.
        assertAmount(statement, "data.totals.retentionAmount", "5000");
        assertAmount(statement, "data.totals.weightKg", "200");
        statement.getInt("data.totals.delegateCount");
    }

    @Test
    void two_campaigns_asked_together_are_summed() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String principale = createCampaign(admin, "Principale", today.minusMonths(9), today.minusMonths(4));
        String intermediaire = createCampaign(admin, "Intermédiaire", today.minusMonths(3), today.plusMonths(2));
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String sectionId = createSection(admin);
        String memberId = createProducer(admin);
        String delegate = createDelegate(admin, "del-c", "BAMBA Sekou", sectionId, 30);

        createReceipt(admin, memberId, articleId, siteId, delegate, 100, today.minusMonths(6), "BRO-010");
        createReceipt(admin, memberId, articleId, siteId, delegate, 50, today.minusMonths(1), "BRO-011");

        // Une seule campagne : seule sa collecte.
        var one = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/statement?campaignId=" + principale)
                .then().statusCode(200).extract().jsonPath();
        assertAmount(one, "data.rows[0].weightKg", "100");

        // Les deux campagnes de la saison, comme le demande l'expert.
        var both = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/statement"
                        + "?campaignId=" + principale + "&campaignId=" + intermediaire)
                .then().statusCode(200).extract().jsonPath();
        assertAmount(both, "data.rows[0].weightKg", "150");
        assertAmount(both, "data.rows[0].retentionAmount", "4500");  // 150 kg x 30
    }

    @Test
    void the_dated_ledger_accumulates_and_carries_the_field_note_number() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(4), today.plusMonths(2));
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String sectionId = createSection(admin);
        String memberId = createProducer(admin);
        String delegate = createDelegate(admin, "del-d", "YAO Kouamé", sectionId, 20);

        openAdvance(admin, delegate, siteId, campaign, 400_000, today.minusMonths(3));
        createReceipt(admin, memberId, articleId, siteId, delegate, 100, today.minusMonths(2), "BRO-100");
        createReceipt(admin, memberId, articleId, siteId, delegate, 150, today.minusMonths(1), "BRO-101");

        var ledger = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/" + delegate
                        + "/ledger?campaignId=" + campaign)
                .then().statusCode(200)
                .body("data.lines", hasSize(3))
                .extract().jsonPath();

        // Ligne 1, l'avance : rien n'est encore livré, tout reste à apurer.
        org.junit.jupiter.api.Assertions.assertEquals("ADVANCE", ledger.getString("data.lines[0].operation"));
        assertAmount(ledger, "data.lines[0].grossBalance", "400000");
        assertAmount(ledger, "data.lines[0].netBalance", "400000");

        // Ligne 2, le premier bordereau : 100 kg à 1 000, 20 FCFA/kg retenus.
        org.junit.jupiter.api.Assertions.assertEquals("BRO-100", ledger.getString("data.lines[1].fieldNoteRef"));
        assertAmount(ledger, "data.lines[1].delivered", "100000");
        assertAmount(ledger, "data.lines[1].retention", "2000");
        assertAmount(ledger, "data.lines[1].netBalance", "298000"); // 400 000 − (100 000 + 2 000)

        // Ligne 3, le second : les grandeurs cumulent, elles ne se
        // remplacent pas. C'est ce cumul qui rend l'état lisible de haut
        // en bas.
        org.junit.jupiter.api.Assertions.assertEquals("BRO-101", ledger.getString("data.lines[2].fieldNoteRef"));
        assertAmount(ledger, "data.lines[2].weightKg", "250");
        assertAmount(ledger, "data.lines[2].delivered", "250000");
        assertAmount(ledger, "data.lines[2].retention", "5000");
        assertAmount(ledger, "data.lines[2].netBalance", "145000"); // 400 000 − (250 000 + 5 000)
        assertAmount(ledger, "data.lines[2].averagePricePerKg", "1000");
        // (I) = H / C = 145 000 / 400 000 = 36,3 %
        assertAmount(ledger, "data.lines[2].repaymentRatePct", "36.3");

        // Une avance n'a pas de bordereau : le numéro de brousse ne se
        // porte que sur ce qui est descendu du terrain.
        org.junit.jupiter.api.Assertions.assertNull(ledger.getString("data.lines[0].fieldNoteRef"));
    }

    @Test
    void the_statement_exports_both_measures_whatever_the_reading_on_screen() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        String sectionId = createSection(admin);
        createDelegate(admin, "del-e", "DIABATE Moussa", sectionId, 15);

        String csv = givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/statement/export"
                        + "?campaignId=" + campaign + "&format=csv")
                .then().statusCode(200).extract().asString();

        // Le fichier porte les deux grandeurs même si l'écran n'en montrait
        // qu'une : sinon il faudrait exporter deux fois pour un tableau.
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Mise en compte"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Marge"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("DIABATE Moussa"), csv);
    }

    @Test
    void a_supplier_who_is_not_a_delegate_stays_out_of_the_statement() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String campaign = createCampaign(admin, "Principale", today.minusMonths(2), today.plusMonths(3));
        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"four-1\",\"name\":\"Emballages du Sud\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201);

        givenAs(admin)
                .when().get("/api/v1/collector-advances/delegates/statement?campaignId=" + campaign)
                .then().statusCode(200)
                .body("data.rows", hasSize(0))
                .body("data.totals.delegateCount", equalTo(0));
    }
}
