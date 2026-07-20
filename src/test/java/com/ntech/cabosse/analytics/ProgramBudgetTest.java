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
import static org.hamcrest.Matchers.hasItem;

/**
 * Comptabilité budgétaire par programme/projet (backlog CPT-10) :
 * référentiel éditable, imputation manuelle sur une OD, contrôle de
 * périmètre (classe 6/7), projet appartenant au programme, état par
 * programme.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProgramBudgetTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-prog-" + TestFixtures.randomSlugSuffix(), "Coopérative Budget");
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

    private void createProgram(UserEntity admin) {
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "code": "DURAB", "name": "Durabilité",
                          "projects": [ { "code": "CERT", "name": "Achat cacao certifié" } ] }
                        """)
                .when().post("/api/v1/programs")
                .then().statusCode(201);
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
    void program_referential_crud_with_projects() {
        UserEntity admin = tenantAdmin();
        createProgram(admin);
        givenAs(admin)
                .when().get("/api/v1/programs")
                .then().statusCode(200)
                .body("data.code", hasItem("DURAB"))
                .body("data.find { it.code == 'DURAB' }.projects.code", hasItem("CERT"));
    }

    @Test
    void od_line_carries_program_and_feeds_the_report() {
        UserEntity admin = tenantAdmin();
        createProgram(admin);
        String today = LocalDate.now().toString();

        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Charge programme",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debitFcfa": 25000,
                      "program": "DURAB", "project": "CERT" },
                    { "account": "401", "libelle": "Dette", "creditFcfa": 25000 }
                  ] }
                """.formatted(today));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);

        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.find { it.syscohadaAccount == '601' }.program",
                        equalTo("DURAB"))
                .body("data.items[0].entries.find { it.syscohadaAccount == '601' }.project",
                        equalTo("CERT"));

        givenAs(admin)
                .when().get("/api/v1/accounting/analytics/programs")
                .then().statusCode(200)
                .body("data.find { it.program == 'DURAB' && it.project == 'CERT' }.chargesFcfa",
                        equalTo(25000));
    }

    @Test
    void program_on_non_class67_line_is_rejected() {
        UserEntity admin = tenantAdmin();
        createProgram(admin);
        String today = LocalDate.now().toString();
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Mauvaise imputation",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debitFcfa": 1000 },
                    { "account": "401", "libelle": "Dette", "creditFcfa": 1000, "program": "DURAB" }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(422);
    }

    @Test
    void project_must_belong_to_program() {
        UserEntity admin = tenantAdmin();
        createProgram(admin);
        String today = LocalDate.now().toString();
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Projet incohérent",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debitFcfa": 1000,
                      "program": "DURAB", "project": "INEXISTANT" },
                    { "account": "401", "libelle": "Dette", "creditFcfa": 1000 }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(422);
    }

    @Test
    void product_line_can_carry_a_program() {
        UserEntity admin = tenantAdmin();
        createProgram(admin);
        String today = LocalDate.now().toString();
        // Programme sur une ligne de produit (701, classe 7) : accepté.
        String odId = createOd(admin, """
                { "date": "%s", "libelle": "Produit programme",
                  "lines": [
                    { "account": "411", "libelle": "Client", "debitFcfa": 30000 },
                    { "account": "701", "libelle": "Vente", "creditFcfa": 30000, "program": "DURAB" }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);

        givenAs(admin)
                .when().get("/api/v1/accounting/analytics/programs")
                .then().statusCode(200)
                .body("data.find { it.program == 'DURAB' && it.project == null }.produitsFcfa",
                        equalTo(30000));
    }
}
