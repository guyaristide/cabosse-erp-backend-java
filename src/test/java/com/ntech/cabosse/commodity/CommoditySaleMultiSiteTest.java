package com.ntech.cabosse.commodity;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Une vente, plusieurs magasins (demandé le 21/10/2026, VTE-16).
 *
 * <p>Le cacao vendu à l'exportateur se constitue à partir de plusieurs
 * sites de la coopérative : un camion part de Méagui, un autre de Touih,
 * et c'est l'ensemble qui fait la vente et sa facture. Chaque chargement
 * est déjà sorti sur son bordereau ; la vente les appelle tous, le poids
 * déclaré les additionne, le coût des ventes aussi, et chaque bordereau
 * se marque vendu une seule fois. Le site de la vente est celui qui
 * facture : le contrôle qui exigeait que le bordereau en soit parti est
 * tombé avec cette lecture.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommoditySaleMultiSiteTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.plan.repository.PlanRepository plans;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-multi-" + TestFixtures.randomSlugSuffix(), "Coopérative Multisites");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        // Deux magasins : le plan de départ n'en autorise qu'un.
        com.ntech.cabosse.plan.entity.PlanEntity plan = new com.ntech.cabosse.plan.entity.PlanEntity();
        plan.code = "multi-" + TestFixtures.randomSlugSuffix();
        plan.name = "Palier multisites";
        plan.maxUsers = 99;
        plan.maxSites = 99;
        plan.maxMembers = 9999;
        plan.active = true;
        plans.persist(plan);
        tenant.planCode = plan.code;
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Multisites";
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

    private record Note(String id, String ref, String siteId) {}

    private record Seed(String articleId, String customerId, Note meagui, Note touih) {}

    private String site(UserEntity admin, String name) {
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    /** Un reçu au magasin, puis le camion qui l'emporte : un bordereau ouvert. */
    private Note note(UserEntity admin, String siteId, String articleId, String customerId,
                      String memberId, int bags, int kg) {
        String receiptId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": %d, "weightKg": %d,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, bags, kg))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");
        var created = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s",
                          "truckNumber": "CI-%d-AB",
                          "lines": [{ "receiptId": "%s", "netKg": %d, "bagsCount": %d }] }
                        """.formatted(LocalDate.now(), siteId, customerId, kg, receiptId, kg, bags))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201).extract();
        return new Note(created.path("data.id"), created.path("data.ref"), siteId);
    }

    /** Méagui charge 3 084 kg (50 sacs), Touih 2 290 kg (34 sacs). */
    private Seed seed(UserEntity admin) {
        String meagui = site(admin, "Méagui");
        String touih = site(admin, "Touih");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        return new Seed(articleId, customerId,
                note(admin, meagui, articleId, customerId, memberId, 50, 3084),
                note(admin, touih, articleId, customerId, memberId, 34, 2290));
    }

    private String status(UserEntity admin, Note n) {
        return givenAs(admin).when().get("/api/v1/dispatch-notes/" + n.id())
                .then().statusCode(200).extract().path("data.status");
    }

    @Test
    void la_vente_reunit_les_chargements_de_deux_sites() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // Le site de la vente est Méagui, qui facture ; le second camion
        // est parti de Touih, et cela ne gêne plus.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteIds": ["%s", "%s"],
                          "weights": { "acceptedKg": 5300 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.meagui().siteId(),
                        s.meagui().id(), s.touih().id()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                // Le poids déclaré est la somme des bordereaux, pas la saisie.
                .body("data.weights.declaredKg", equalTo(5374))
                .body("data.dispatchNoteRefs", hasSize(2))
                .body("data.dispatchNoteId", equalTo(s.meagui().id()))
                .body("data.loads", hasSize(2))
                .body("data.loads[1].siteName", equalTo("Touih"))
                .body("data.loads[1].declaredKg", equalTo(2290))
                .body("data.loads[1].bags", equalTo(34))
                // Le coût des ventes additionne les deux sorties au CMUP de chacune.
                .body("data.cogs", equalTo(5374000.0F))
                .body("data.movementRef", containsString(s.touih().ref()));

        // Les deux bordereaux sont vendus, et aucun second mouvement de
        // stock n'a eu lieu : les sites restent à zéro.
        org.junit.jupiter.api.Assertions.assertEquals("SOLD", status(admin, s.meagui()));
        org.junit.jupiter.api.Assertions.assertEquals("SOLD", status(admin, s.touih()));
        givenAs(admin).when().get("/api/v1/stocks/" + s.articleId() + "/sites/" + s.touih().siteId())
                .then().statusCode(200).body("data.quantity", equalTo(0));
    }

    @Test
    void un_bordereau_deja_vendu_fait_echouer_la_vente_sans_en_marquer_aucun() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // Méagui part seul dans une première vente.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteId": "%s",
                          "weights": { "acceptedKg": 3000 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.meagui().siteId(),
                        s.meagui().id()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201);

        // La seconde vente appelle Touih et, par erreur, Méagui déjà vendu.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteIds": ["%s", "%s"],
                          "weights": { "acceptedKg": 5300 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.touih().siteId(),
                        s.touih().id(), s.meagui().id()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(422)
                .body("statusMessage", containsString(s.meagui().ref()));

        // Touih n'a pas été marqué au passage : il reste à vendre.
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", status(admin, s.touih()));
    }

    @Test
    void le_fichier_porte_un_chargement_par_ligne_et_une_seule_vente() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // Comme l'expert l'écrit : la première ligne porte tout, la
        // seconde n'a que son bordereau, son lieu de départ et son poids.
        String rows = """
                [{ "rowNumber": 2, "customerName": "ZAMACOM", "productCode": "Fèves séchées",
                   "date": "%1$s", "siteId": "%2$s", "dispatchNoteNumber": "%3$s", "loadingNumber": "5",
                   "departureLocation": "Méagui",
                   "declaredKg": "3084", "dischargedKg": "5370", "acceptedKg": "5300",
                   "montantFacture": "6360000" },
                 { "rowNumber": 3, "customerName": "ZAMACOM", "productCode": "Fèves séchées",
                   "date": "%1$s", "siteId": "%2$s", "dispatchNoteNumber": "%4$s", "loadingNumber": "6",
                   "departureLocation": "Touih", "declaredKg": "2290" }]
                """.formatted(LocalDate.now(), s.meagui().siteId(), s.meagui().ref(), s.touih().ref());

        givenAs(admin).contentType("application/json").body(rows)
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.invalidRows", equalTo(0))
                .body("data.rows[0].status", equalTo("READY"))
                // Le poids déclaré de la vente additionne ses chargements.
                .body("data.rows[0].normalized.declaredKg", equalTo(5374))
                .body("data.rows[1].status", equalTo("ATTACHED"));

        givenAs(admin).contentType("application/json").body(rows)
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.skippedCount", equalTo(0));

        org.junit.jupiter.api.Assertions.assertEquals("SOLD", status(admin, s.meagui()));
        org.junit.jupiter.api.Assertions.assertEquals("SOLD", status(admin, s.touih()));

        // Le carnet de sorties retrouve la vente par chaque chargement.
        givenAs(admin).when().get("/api/v1/commodity/sales")
                .then().statusCode(200)
                .body("data.items[0].loads", hasSize(2))
                .body("data.items[0].loads[1].loadingNumber", equalTo("6"));
    }

    @Test
    void un_chargement_sans_vente_a_rejoindre_est_refuse() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        String rows = """
                [{ "rowNumber": 2, "customerName": "ZAMACOM", "productCode": "Fèves séchées",
                   "date": "%s", "siteId": "%s", "dispatchNoteNumber": "%s", "declaredKg": "2290" }]
                """.formatted(LocalDate.now(), s.touih().siteId(), s.touih().ref());

        givenAs(admin).contentType("application/json").body(rows)
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(0))
                .body("data.rows[0].status", equalTo("INVALID"))
                .body("data.rows[0].issues[0].message", containsString("rejoindre"));
    }
}
