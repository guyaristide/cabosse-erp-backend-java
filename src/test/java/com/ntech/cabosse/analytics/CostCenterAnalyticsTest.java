package com.ntech.cabosse.analytics;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Comptabilité analytique par centre de coût (backlog CPT-09) : imputation
 * manuelle sur une OD, refus hors classe 6, centre inconnu rejeté,
 * exigence paramétrable, état par centre.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CostCenterAnalyticsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cc-" + TestFixtures.randomSlugSuffix(), "Coopérative Analytique");
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

    private String createOd(UserEntity admin, String body) {
        return givenAs(admin)
                .contentType("application/json")
                .body(body)
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void cost_center_referential_crud() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "EXPORT", "Export et logistique");
        givenAs(admin)
                .when().get("/api/v1/cost-centers")
                .then().statusCode(200)
                .body("data.code", hasItem("EXPORT"));
    }

    private void createCostCenter(UserEntity admin, String code, String name) {
        givenAs(admin)
                .contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/cost-centers")
                .then().statusCode(201);
    }

    @Test
    void od_line_carries_cost_center_and_feeds_the_report() {
        UserEntity admin = tenantAdmin();
        String today = LocalDate.now().toString();

        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Dépense collecte",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debit": 40000, "costCenter": "COL" },
                    { "account": "401", "libelle": "Dette", "credit": 40000 }
                  ] }
                """.formatted(today));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);

        // Le centre de coût est bien porté par la ligne au journal.
        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.find { it.syscohadaAccount == '601' }.costCenter",
                        equalTo("COL"));

        // Et il alimente l'état analytique.
        givenAs(admin)
                .when().get("/api/v1/accounting/analytics/cost-centers")
                .then().statusCode(200)
                .body("data.find { it.code == 'COL' }.charges", equalTo(40000.0F));
    }

    @Test
    void cost_center_on_non_class6_line_is_rejected() {
        UserEntity admin = tenantAdmin();
        String today = LocalDate.now().toString();
        // Centre de coût posé sur une ligne de tiers (401, classe 4).
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Mauvaise imputation",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debit": 1000 },
                    { "account": "401", "libelle": "Dette", "credit": 1000, "costCenter": "COL" }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(422);
    }

    @Test
    void unknown_cost_center_is_rejected() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "COL", "Collecte");
        String today = LocalDate.now().toString();
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Centre inexistant",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debit": 1000, "costCenter": "ZZZ" },
                    { "account": "401", "libelle": "Dette", "credit": 1000 }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(422);
    }

    @Test
    void cost_center_requirement_is_configurable() {
        UserEntity admin = tenantAdmin();
        givenAs(admin)
                .contentType("application/json")
                .body("{\"costCenterRequired\":true}")
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.costCenterRequired", equalTo(true));

        String today = LocalDate.now().toString();
        // Ligne de charge sans centre : refusée quand l'exigence est active.
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Charge sans centre",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debit": 1000 },
                    { "account": "401", "libelle": "Dette", "credit": 1000 }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(422);
    }

    @Test
    void report_without_data_is_empty_but_ok() {
        UserEntity admin = tenantAdmin();
        givenAs(admin)
                .when().get("/api/v1/accounting/analytics/cost-centers")
                .then().statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(0));
    }
}
