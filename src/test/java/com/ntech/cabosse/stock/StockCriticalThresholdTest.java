package com.ntech.cabosse.stock;

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
 * Palier d'alerte critique du stock (préférence stockMinWarningPct) : un
 * article passe en critique quand sa quantité tombe sous ce pourcentage de
 * son seuil d'alerte. Le pourcentage est un vrai réglage tenant.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockCriticalThresholdTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-crit-" + TestFixtures.randomSlugSuffix(), "Coopérative Stock critique");
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

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void stock_goes_critical_below_warning_pct_of_threshold() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);

        // Article avec seuil d'alerte 100. Critique par défaut sous 20 % (= 20).
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao\",\"unit\":\"kg\",\"alertThreshold\":100}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Producteur\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // Réception de 15 kg (< 20 : critique).
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "receivedDate": "%s",
                          "lines": [ { "supplierId": "%s", "quantity": 15, "unitPrice": 1500 } ] }
                        """.formatted(articleId, LocalDate.now(), supplierId))
                .when().post("/api/v1/direct-receipts?siteId=" + siteId).then().statusCode(201);

        givenAs(admin).when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.belowThreshold", equalTo(true))
                .body("data.critical", equalTo(true));

        // Abaisser le pourcentage à 10 % (= seuil critique 10) : 15 n'est plus critique.
        givenAs(admin).contentType("application/json")
                .body("{\"stockMinWarningPct\":10}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        givenAs(admin).when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.belowThreshold", equalTo(true))
                .body("data.critical", equalTo(false));
    }
}
