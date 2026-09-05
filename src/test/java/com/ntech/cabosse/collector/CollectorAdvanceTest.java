package com.ntech.cabosse.collector;

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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Avances aux délégués collecteurs (backlog ACH-02) : versement d'avance,
 * livraisons de matière imputées sur l'avance jusqu'au solde, garde du
 * dépassement, clôture. Écritures 4091/trésorerie et 601/4091.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectorAdvanceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-av-" + TestFixtures.randomSlugSuffix(), "Coopérative Avances");
        tenant.organizationModel =
                com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
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

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createSection(UserEntity admin, String code, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true,\"sectionId\":\"" + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void section_referential_crud() {
        UserEntity admin = tenantAdmin();
        createSection(admin, "MEAGUI", "Section Méagui");
        givenAs(admin).when().get("/api/v1/sections")
                .then().statusCode(200)
                .body("data.find { it.code == 'MEAGUI' }.name", equalTo("Section Méagui"));
    }

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
    }

    /** Reçu d'achat producteur payé par le délégué : la seule voie d'apurement. */
    private void buyFromProducer(UserEntity admin, String delegateId, String memberId,
                                 String articleId, String siteId, int kg, int pricePerKg) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKg": %d,
                          "paymentMethod": "CASH", "delegateSupplierId": "%s" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                                kg, pricePerKg, delegateId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases")
                .then().statusCode(201);
    }

    @Test
    void advance_deliveries_and_balance() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "SOUBRE", "Section Soubré");
        String delegateId = createDelegate(admin, "Délégué Kouassi", sectionId);
        String articleId = createArticle(admin, "Cacao marchand");
        String siteId = createSite(admin);

        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 1000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .body("data.remaining", equalTo(1000000))
                .body("data.sectionName", equalTo("Section Soubré"))
                .extract().path("data.id");

        // Une demande ne sort pas d'argent : le journal ne porte encore que
        // l'amorçage de la caisse.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(1));

        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(1));

        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                .then().statusCode(200).body("data.status", equalTo("OPEN"));

        // Pièce d'avance au journal (4091 / trésorerie), au décaissement.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(2));

        String memberId = createProducer(admin, "Kouadio");

        // 500 kg à 1500 = 750 000 apurés sur le compte du délégué.
        buyFromProducer(admin, delegateId, memberId, articleId, siteId, 500, 1500);
        givenAs(admin).when().get("/api/v1/collector-advances/" + advanceId)
                .then().statusCode(200)
                .body("data.consumedAmount", equalTo(750000))
                .body("data.remaining", equalTo(250000));

        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).body("data.total", equalTo(3));

        // Livraison qui dépasse ce qu'il a reçu : acceptée, son solde
        // devient créditeur. La coopérative lui doit alors la différence,
        // qui se compensera au versement suivant.
        buyFromProducer(admin, delegateId, memberId, articleId, siteId, 200, 1500);
        givenAs(admin).when().get("/api/v1/collector-advances/" + advanceId)
                .then().statusCode(200)
                .body("data.remaining", equalTo(-50000));

        // Le compte courant du délégué dit la même chose, tous versements
        // et tous bordereaux confondus.
        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalAdvanced", equalTo(1000000))
                .body("data.totalDelivered", equalTo(1050000))
                .body("data.balance", equalTo(-50000))
                .body("data.deliveryNotes", org.hamcrest.Matchers.hasSize(2));

        // Clôture au décompte de fin de campagne.
        givenAs(admin).contentType("application/json").body("{\"note\":\"Fin de campagne\"}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/close")
                .then().statusCode(200).body("data.status", equalTo("CLOSED"));
    }

    @Test
    void advance_requires_a_collector_supplier() {
        UserEntity admin = tenantAdmin();
        // Fournisseur ordinaire (pas délégué).
        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Fournisseur ordinaire\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 100000, "paymentMethod": "CASH" }
                        """.formatted(supplierId, LocalDate.now()))
                .when().post("/api/v1/collector-advances")
                .then().statusCode(422);
    }
}
