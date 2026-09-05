package com.ntech.cabosse.purchaserequest;

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
import static org.hamcrest.Matchers.notNullValue;

/**
 * Demande d'achat et seuil de contrôle interne (backlog ACH-01) :
 * workflow soumission/approbation, conversion en BC, et garde du seuil
 * paramétrable sur la confirmation du BC.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PurchaseRequestTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-da-" + TestFixtures.randomSlugSuffix(), "Coopérative DA");
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

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles")
                .then().statusCode(201).extract().path("data.id");
    }

    private String createSupplier(UserEntity admin, String name) {
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/api/v1/suppliers")
                .then().statusCode(201).extract().path("data.id");
    }

    private String createRequest(UserEntity admin, String articleId, String supplierId, int qty, int pu) {
        String supplierField = supplierId != null ? "\"supplierId\": \"" + supplierId + "\"," : "";
        return givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "requestDate": "%s", %s "justification": "Besoin campagne",
                          "lines": [ { "articleId": "%s", "quantity": %d, "estimatedUnitPrice": %d } ] }
                        """.formatted(LocalDate.now(), supplierField, articleId, qty, pu))
                .when().post("/api/v1/purchase-requests")
                .then().statusCode(201).extract().path("data.id");
    }

    @Test
    void submit_approve_then_convert_to_order() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Engrais NPK");
        String supplierId = createSupplier(admin, "AgroFournitures");
        String daId = createRequest(admin, articleId, supplierId, 100, 1500);

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-requests/" + daId + "/submit")
                .then().statusCode(200).body("data.status", equalTo("SUBMITTED"));

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-requests/" + daId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/purchase-requests/" + daId + "/convert")
                .then().statusCode(200)
                .body("data.status", equalTo("CONVERTED"))
                .body("data.convertedOrderRef", notNullValue());
    }

    @Test
    void reject_requires_a_reason() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Urée");
        String daId = createRequest(admin, articleId, null, 50, 1000);
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-requests/" + daId + "/submit")
                .then().statusCode(200);
        // Rejet sans motif refusé.
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/purchase-requests/" + daId + "/reject")
                .then().statusCode(422);
        givenAs(admin).contentType("application/json").body("{\"reason\":\"Budget dépassé\"}")
                .when().post("/api/v1/purchase-requests/" + daId + "/reject")
                .then().statusCode(200).body("data.status", equalTo("REJECTED"));
    }

    @Test
    void threshold_blocks_direct_order_confirmation_when_enabled() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Bascule");
        String supplierId = createSupplier(admin, "Matériel SARL");

        // Active le circuit avec un seuil bas.
        givenAs(admin).contentType("application/json")
                .body("{\"purchaseRequestEnabled\":true,\"purchaseRequestThreshold\":10000}")
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.purchaseRequestEnabled", equalTo(true));

        // BC direct au-dessus du seuil : confirmation refusée.
        String bcId = givenAs(admin).contentType("application/json")
                .body("""
                        { "supplierId": "%s", "orderDate": "%s", "vatRatePct": 0,
                          "lines": [ { "articleId": "%s", "quantity": 1, "unitPrice": 500000 } ] }
                        """.formatted(supplierId, LocalDate.now(), articleId))
                .when().post("/api/v1/purchase-orders")
                .then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-orders/" + bcId + "/confirm")
                .then().statusCode(422);

        // Un BC issu d'une DA approuvée passe la confirmation.
        String daId = createRequest(admin, articleId, supplierId, 1, 500000);
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-requests/" + daId + "/submit").then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-requests/" + daId + "/approve").then().statusCode(200);
        String orderId = givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/purchase-requests/" + daId + "/convert")
                .then().statusCode(200).extract().path("data.convertedOrderId");

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-orders/" + orderId + "/confirm")
                .then().statusCode(200).body("data.status", equalTo("CONFIRMED"));
    }

    @Test
    void threshold_disabled_allows_direct_confirmation() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Papier");
        String supplierId = createSupplier(admin, "Bureautique");
        String bcId = givenAs(admin).contentType("application/json")
                .body("""
                        { "supplierId": "%s", "orderDate": "%s", "vatRatePct": 0,
                          "lines": [ { "articleId": "%s", "quantity": 1, "unitPrice": 999999 } ] }
                        """.formatted(supplierId, LocalDate.now(), articleId))
                .when().post("/api/v1/purchase-orders")
                .then().statusCode(201).extract().path("data.id");
        // Circuit désactivé par défaut : confirmation directe possible.
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/purchase-orders/" + bcId + "/confirm")
                .then().statusCode(200).body("data.status", equalTo("CONFIRMED"));
    }
}
