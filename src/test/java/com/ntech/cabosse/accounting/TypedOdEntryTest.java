package com.ntech.cabosse.accounting;

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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Les écritures types (CE-211) : paie, corrections, inventaire,
 * amortissements, provisions, régularisation. Modèle d'import au gabarit
 * maison, brouillon d'OD marqué de son type, validation par le verrou
 * ordinaire des OD ; les à-nouveaux gardent leur chemin et leur droit.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TypedOdEntryTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-typed-" + TestFixtures.randomSlugSuffix(), "Coopérative Écritures types");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Types";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new java.util.HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    @Test
    void a_typed_entry_goes_from_template_to_draft_to_journal() {
        UserEntity admin = admin();

        // ─── Le modèle se télécharge, un type inconnu est refusé ───
        givenAs(admin).when()
                .get("/api/v1/accounting/od/import/template?type=PAYROLL&format=csv")
                .then().statusCode(200)
                .header("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "modele-ecritures-paie"));
        givenAs(admin).when()
                .get("/api/v1/accounting/od/import/template?type=INCONNU")
                .then().statusCode(400);

        // ─── Le brouillon porte son type et se valide comme une OD ───
        // Les comptes du modèle descendent plus finement que le plan de
        // départ : l'écran les ouvre au passage, le test fait de même.
        givenAs(admin).contentType("application/json")
                .body("{\"number\":\"681000\",\"label\":\"Dotations aux amortissements\"}")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("{\"number\":\"284000\",\"label\":\"Amortissements cumulés\"}")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Amortissements 2026",
                          "kind": "DEPRECIATION",
                          "lines": [
                            { "account": "681000", "libelle": "Dotation", "debit": 500000 },
                            { "account": "284000", "libelle": "Amortissements cumulés",
                              "credit": 500000 } ] }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .body("data.kind", equalTo("DEPRECIATION"))
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200)
                .body("data.status", equalTo("VALIDATED"))
                .body("data.pieceRef", notNullValue());

        // ─── Un type hors catalogue retombe sur l'OD libre ───
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "OD libre", "kind": "AN",
                          "lines": [
                            { "account": "601000", "libelle": "x", "debit": 1000 },
                            { "account": "571000", "libelle": "x", "credit": 1000 } ] }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .body("data.kind", org.hamcrest.Matchers.nullValue());
    }
}
