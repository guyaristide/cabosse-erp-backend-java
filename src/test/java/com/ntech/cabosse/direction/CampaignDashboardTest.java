package com.ntech.cabosse.direction;

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
import static org.hamcrest.Matchers.nullValue;

/**
 * Vue campagne du tableau de bord Direction (épic CE-196, CE-197 et
 * CE-200, modèle expert du 05/09/2026).
 *
 * <p>Les douze indicateurs se recalculent depuis les flux estampillés
 * par la campagne ; la synthèse confronte les taux aux objectifs de
 * préférence tenant ; le résultat net et la marge nette restent vides
 * tant que DEC-39 n'est pas tranchée, plutôt que d'afficher un chiffre
 * faux.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignDashboardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-pilotage-" + TestFixtures.randomSlugSuffix(), "Coopérative Pilotage");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Pilotage";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 100_000_000);
        return u;
    }

    @Test
    void the_campaign_view_reads_every_flow_stamped_by_the_campaign() {
        UserEntity admin = admin();

        // ─── La campagne en cours, de septembre à août ───
        LocalDate today = LocalDate.now();
        LocalDate start = today.withDayOfMonth(1);
        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne pilotage", "kind": "MAIN",
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 900 }
                        """.formatted(start, start.plusMonths(11).withDayOfMonth(28)))
                .when().post("/api/v1/campaigns").then().statusCode(201);

        // ─── Le décor : site, article, membre, client, délégué ───
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin pilotage\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"pil-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Matière séchée\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Aka\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Exportateur Pilotage\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Pilotage\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // ─── Une avance décaissée de 5 000 000, jamais remboursée ───
        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 5000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, today))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CASH\" }")
                .when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                .then().statusCode(200);

        // ─── Deux reçus producteurs : 3 000 kg à 1 000 et 2 000 kg à 1 100 ───
        String[][] receipts = {{"3000", "1000"}, {"2000", "1100"}};
        String firstReceiptId = null;
        for (String[] r : receipts) {
            String id = givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "weightKg": %s, "guaranteedPricePerKg": %s, "paymentMethod": "CASH" }
                            """.formatted(today, memberId, articleId, siteId, r[0], r[1]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201)
                    .extract().path("data.id");
            if (firstReceiptId == null) firstReceiptId = id;
        }

        // ─── Un chargement de 3 000 kg, vendu 2 900 kg acceptés à 1 500 ───
        String noteId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s",
                          "lines": [ { "receiptId": "%s", "netKg": 3000 } ] }
                        """.formatted(today, siteId, customerId, firstReceiptId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteId": "%s",
                          "weights": { "acceptedKg": 2900 }, "pricePerKg": 1500 }
                        """.formatted(today, customerId, articleId, siteId, noteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201);

        // ─── Les douze indicateurs ───
        // Prix moyen d'achat : 5 200 000 / 5 000 = 1 040. Coût des ventes :
        // 3 000 kg au CMUP 1 040 = 3 120 000 ; CA HT 2 900 × 1 500 = 4 350 000 ;
        // marge 1 230 000. Stock restant : 2 000 kg.
        givenAs(admin).when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.weightUnit", equalTo("kg"))
                .body("data.kpis.purchasedWeight", equalTo(5000))
                .body("data.kpis.soldWeight", equalTo(2900))
                .body("data.kpis.stockWeight", equalTo(2000))
                .body("data.kpis.revenue", equalTo(4350000))
                // La marge sort en décimal (coût des ventes à virgule).
                .body("data.kpis.grossMargin", equalTo(1230000.0F))
                .body("data.kpis.netResult", nullValue())
                .body("data.kpis.advancesDisbursed", equalTo(5000000))
                .body("data.kpis.advancesOutstanding", equalTo(5000000))
                // Rien n'est revenu : couverture nulle.
                .body("data.kpis.advanceCoverageRatePct", equalTo(0.0F))
                .body("data.kpis.avgPurchasePricePerKg", equalTo(1040.0F))
                .body("data.kpis.avgSalePricePerKg", equalTo(1500.0F))
                .body("data.kpis.avgUnitMargin", equalTo(460.0F))
                // ─── La synthèse : constats contre objectifs ───
                // 1 230 000 × 100 / 4 350 000 = 28,3 %.
                .body("data.synthesis.grossMarginRatePct", equalTo(28.3F))
                .body("data.synthesis.grossMarginTargetPct", equalTo(20))
                .body("data.synthesis.netMarginRatePct", nullValue())
                .body("data.synthesis.netMarginTargetPct", equalTo(2))
                // La caisse est restée largement positive : besoin nul.
                .body("data.synthesis.maxFinancingNeed", equalTo(0))
                .body("data.synthesis.treasuryLowPointMonth", equalTo(YearMonth.from(start).toString()))
                .body("data.synthesis.residualStockWeight", equalTo(2000))
                .body("data.synthesis.unsettledDelegatesCount", equalTo(1))
                // ─── Les courbes mensuelles (tout s'est passé ce mois-ci) ───
                .body("data.months.find { it.month == '%s' }.revenue".formatted(YearMonth.from(start)),
                        equalTo(4350000))
                .body("data.months.find { it.month == '%s' }.purchasedWeight".formatted(YearMonth.from(start)),
                        equalTo(5000))
                .body("data.months.find { it.month == '%s' }.soldWeight".formatted(YearMonth.from(start)),
                        equalTo(2900))
                // ─── La part de chaque client dans les volumes ───
                .body("data.customers", hasSize(1))
                .body("data.customers[0].customerName", equalTo("Exportateur Pilotage"))
                .body("data.customers[0].soldWeight", equalTo(2900))
                // ─── Le volet par délégué : avance contre remboursé ───
                .body("data.delegates", hasSize(1))
                .body("data.delegates[0].delegateName", equalTo("Délégué Pilotage"))
                .body("data.delegates[0].advanced", equalTo(5000000))
                .body("data.delegates[0].reimbursed", equalTo(0))
                .body("data.delegates[0].outstanding", equalTo(5000000));

        // ─── L'objectif est une préférence tenant, pas une constante ───
        givenAs(admin).contentType("application/json")
                .body("{ \"grossMarginTargetPct\": 30, \"netMarginTargetPct\": 5 }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        givenAs(admin).when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200)
                .body("data.synthesis.grossMarginTargetPct", equalTo(30))
                .body("data.synthesis.netMarginTargetPct", equalTo(5));
    }

    @Test
    void without_a_campaign_the_view_says_so_instead_of_showing_zeros() {
        UserEntity admin = admin();
        givenAs(admin).when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(404);
    }
}
