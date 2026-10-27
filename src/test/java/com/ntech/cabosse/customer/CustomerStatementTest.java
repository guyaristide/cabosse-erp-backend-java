package com.ntech.cabosse.customer;

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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * L'état d'un client (demandé le 27/10/2026) : depuis sa fiche, tout ce
 * que la structure sait de lui, ventes, encaissements et contrats, en
 * agrégats et en fil d'opérations, et en fichier.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CustomerStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-etat-cli-" + TestFixtures.randomSlugSuffix(), "Coopérative État Client");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity = new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "État";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    /** Les montants de vente portent deux décimales : le JSON les rend en flottant, les entiers restent entiers. */
    private static org.hamcrest.Matcher<Object> amount(int value) {
        return anyOf(equalTo(value), equalTo((float) value));
    }

    @Test
    void the_statement_adds_up_the_customer_s_sales_and_lists_the_payments() {
        UserEntity admin = admin();
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Boutique\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"FINISHED_PRODUCT\",\"name\":\"Savon de ménage\",\"unit\":\"pièce\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\",\"cityName\":\"Abidjan\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");

        // Une vente actée directement, puis un premier versement.
        String saleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 10, "unitPrice": 2000 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=false")
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "paidOn": "%s", "amount": 5000, "method": "CASH", "paymentNoteRef": "BR-001" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/sales/" + saleId + "/payments").then().statusCode(200);

        givenAs(admin).when().get("/api/v1/customers/" + customerId + "/statement")
                .then().statusCode(200)
                .body("data.header.name", equalTo("ZAMACOM"))
                .body("data.header.type", equalTo("COMPANY"))
                .body("data.header.cityName", equalTo("Abidjan"))
                .body("data.totals.quotesCount", equalTo(0))
                .body("data.totals.salesCount", equalTo(1))
                .body("data.totals.salesAmount", amount(20000))
                .body("data.totals.salesPaid", amount(5000))
                .body("data.totals.invoicedAmount", amount(20000))
                .body("data.totals.paidAmount", amount(5000))
                .body("data.totals.receivable", amount(15000))
                .body("data.operations.size()", greaterThanOrEqualTo(2))
                .body("data.operations.find { it.type == 'PAYMENT' }.amount", amount(5000))
                .body("data.operations.find { it.type == 'PAYMENT' }.sourceType", equalTo("SALE"))
                .body("data.operations.find { it.type == 'PAYMENT' }.sourceId", equalTo(saleId))
                .body("data.campaigns.size()", greaterThanOrEqualTo(1));

        // En fichier, le fil entier, sous le même titre.
        String csv = givenAs(admin).when().get("/api/v1/customers/" + customerId + "/statement/export?format=csv")
                .then().statusCode(200).contentType(containsString("text/csv")).extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Encaissement"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Vente"), csv);

        // Une campagne qui n'a rien reçu rend un état vide, pas une erreur.
        givenAs(admin).when().get("/api/v1/customers/" + customerId + "/statement?campaignId=" + UUID.randomUUID())
                .then().statusCode(200)
                .body("data.totals.salesCount", equalTo(0))
                .body("data.operations.size()", equalTo(0));
    }
}
