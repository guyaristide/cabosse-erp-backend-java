package com.ntech.cabosse.producerpayment;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Règlements des livraisons de matière première.
 *
 * <p>La coopérative encaisse tard : une livraison de plusieurs dizaines de
 * millions se règle en plusieurs fois, sur des semaines. Ce que le
 * comptable doit pouvoir lire à tout moment, sans le reconstituer à la
 * main : ce qui a été versé sur cette livraison-là, et ce qui reste.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProducerPaymentTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-regl-" + TestFixtures.randomSlugSuffix(), "Coopérative Règlements");
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

        // Sans paiement fractionné, une livraison est réputée soldée au
        // reçu : c'est ce réglage qui ouvre l'échéancier.
        givenAs(u).contentType("application/json")
                .body("{\"producerPartialPaymentEnabled\":true}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return u;
    }

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name) {
        String code = "del-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    /** Reçu d'achat dont rien n'est versé au comptoir. */
    private String unpaidReceipt(UserEntity admin, String memberId, String articleId,
                                 String siteId, int weightKg, int pricePerKg, String delegateId) {
        String delegatePart = delegateId != null
                ? ", \"delegateSupplierId\": \"" + delegateId + "\"" : "";
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKgFcfa": %d,
                          "paymentMethod": "BANK_TRANSFER", "amountPaidFcfa": 0%s }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                        weightKg, pricePerKg, delegatePart))
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");
    }

    private void pay(UserEntity admin, String delegateId, String purchaseId, long amount, String ref) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "paymentMethod": "BANK_TRANSFER",
                          "date": "%s", "paymentRef": "%s",
                          "allocations": [ { "purchaseId": "%s", "amountFcfa": %d } ] }
                        """.formatted(delegateId, LocalDate.now(), ref, purchaseId, amount))
                .when().post("/api/v1/producer-payments").then().statusCode(201);
    }

    @Test
    void a_large_delivery_is_settled_in_instalments() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Kouadio");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String delegateId = createDelegate(admin, "Délégué Bangolo");

        // 40 tonnes à 1 000 : quarante millions dus au délégué.
        String purchaseId = unpaidReceipt(admin, memberId, articleId, siteId, 40000, 1000, delegateId);

        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                .body("data.amountFcfa", equalTo(40000000))
                .body("data.amountPaidFcfa", equalTo(0))
                .body("data.remainderFcfa", equalTo(40000000));

        pay(admin, delegateId, purchaseId, 15000000, "VIR-001");
        pay(admin, delegateId, purchaseId, 15000000, "VIR-002");

        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                .body("data.amountPaidFcfa", equalTo(30000000))
                .body("data.remainderFcfa", equalTo(10000000));

        // L'échéancier ne montre plus que le solde.
        givenAs(admin).when().get("/api/v1/producer-payments/outstanding")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(10000000))
                .body("data.beneficiaries", hasSize(1))
                .body("data.beneficiaries[0].name", equalTo("Délégué Bangolo"))
                .body("data.beneficiaries[0].lines[0].remainingFcfa", equalTo(10000000));

        pay(admin, delegateId, purchaseId, 10000000, "VIR-003");

        // Soldée, la livraison quitte l'échéancier.
        givenAs(admin).when().get("/api/v1/producer-payments/outstanding")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(0))
                .body("data.beneficiaries", hasSize(0));

        // Les trois versements restent lisibles depuis la livraison.
        givenAs(admin).when().get("/api/v1/producer-payments/by-purchase/" + purchaseId)
                .then().statusCode(200)
                .body("data", hasSize(3))
                .body("data.paymentRef", hasItem("VIR-002"))
                .body("data[2].allocations[0].remainingAfterFcfa", equalTo(0));
    }

    @Test
    void nothing_can_be_paid_twice_on_the_same_delivery() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Traoré");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String delegateId = createDelegate(admin, "Délégué Duékoué");

        String purchaseId = unpaidReceipt(admin, memberId, articleId, siteId, 1000, 1000, delegateId);
        pay(admin, delegateId, purchaseId, 900000, "VIR-010");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "paymentMethod": "CASH", "date": "%s",
                          "allocations": [ { "purchaseId": "%s", "amountFcfa": 200000 } ] }
                        """.formatted(delegateId, LocalDate.now(), purchaseId))
                .when().post("/api/v1/producer-payments")
                .then().statusCode(422)
                .body("statusMessage", containsString("il ne reste que"));

        // Le refus n'a rien consommé.
        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                .body("data.amountPaidFcfa", equalTo(900000))
                .body("data.remainderFcfa", equalTo(100000));
    }

    @Test
    void a_delivery_brought_by_a_delegate_is_not_paid_to_the_producer() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Coulibaly");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String delegateId = createDelegate(admin, "Délégué Man");

        String purchaseId = unpaidReceipt(admin, memberId, articleId, siteId, 500, 1000, delegateId);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "paymentMethod": "CASH", "date": "%s",
                          "allocations": [ { "purchaseId": "%s", "amountFcfa": 500000 } ] }
                        """.formatted(memberId, LocalDate.now(), purchaseId))
                .when().post("/api/v1/producer-payments")
                .then().statusCode(422)
                .body("statusMessage", containsString("se règle au délégué"));
    }

    @Test
    void one_payment_can_settle_several_deliveries_at_once() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Bakayoko");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        String first = unpaidReceipt(admin, memberId, articleId, siteId, 300, 1000, null);
        String second = unpaidReceipt(admin, memberId, articleId, siteId, 200, 1000, null);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "paymentMethod": "MOBILE_MONEY", "date": "%s",
                          "paymentRef": "MM-77",
                          "allocations": [ { "purchaseId": "%s", "amountFcfa": 300000 },
                                           { "purchaseId": "%s", "amountFcfa": 150000 } ] }
                        """.formatted(memberId, LocalDate.now(), first, second))
                .when().post("/api/v1/producer-payments")
                .then().statusCode(201)
                .body("data.totalAmountFcfa", equalTo(450000))
                .body("data.allocations", hasSize(2))
                .body("data.ref", containsString("REG-"));

        // Le producteur reste créancier de la seule fraction non versée.
        givenAs(admin).when().get("/api/v1/producer-payments/outstanding")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(50000));

        // L'écriture solde la dette constituée au reçu.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("401100"));
    }

    @Test
    void the_debt_names_who_is_owed() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Ouattara");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String delegateId = createDelegate(admin, "Délégué Toulepleu");

        // Livraison apportée par un délégué : c'est lui le créancier, il a
        // déjà payé le producteur sur son avance.
        String viaDelegate = unpaidReceipt(admin, memberId, articleId, siteId, 400, 1000, delegateId);
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("401200"));

        pay(admin, delegateId, viaDelegate, 400000, "VIR-900");
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("401200"));

        // Livraison en direct : la dette reste au compte des producteurs.
        unpaidReceipt(admin, memberId, articleId, siteId, 100, 1000, null);
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("401100"));
    }

    @Test
    void the_delegate_account_counts_what_was_paid_to_him() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Sangaré");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String delegateId = createDelegate(admin, "Délégué Guiglo");

        // Il livre pour 2 000 000 sans avoir reçu d'avance : la
        // coopérative lui doit tout.
        String purchaseId = unpaidReceipt(admin, memberId, articleId, siteId, 2000, 1000, delegateId);

        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalDeliveredFcfa", equalTo(2000000))
                .body("data.totalPaidFcfa", equalTo(0))
                .body("data.balanceFcfa", equalTo(-2000000));

        pay(admin, delegateId, purchaseId, 2000000, "VIR-500");

        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalPaidFcfa", equalTo(2000000))
                .body("data.payments", hasSize(1))
                .body("data.payments[0].paymentRef", equalTo("VIR-500"))
                .body("data.balanceFcfa", equalTo(0));
    }
}
