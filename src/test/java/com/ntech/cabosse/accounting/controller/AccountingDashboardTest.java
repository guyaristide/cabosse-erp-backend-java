package com.ntech.cabosse.accounting.controller;

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
 * Tableau de bord comptable et plan de comptes, sur une base qui porte des
 * écritures.
 *
 * <p>Les deux passent par la même agrégation, qui compte les écritures par
 * compte. Ce compteur n'était lu que dans un type : à vide l'agrégation ne
 * renvoie rien et tout paraissait sain, la première pièce enregistrée
 * suffisait à faire tomber l'écran d'accueil de la comptabilité.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AccountingDashboardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dash-" + TestFixtures.randomSlugSuffix(), "Coopérative Tableau de bord");
        // Le tableau de bord s'appuie sur le plan de comptes semé.
        migrations.runMigrationsFor(tenant.databaseName);

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

    /** Une pièce validée au journal, pour que l'agrégation ait de quoi compter. */
    private void postEntry(UserEntity admin, int amount) {
        LocalDate today = LocalDate.now();
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Régularisation",
                          "lines": [
                            { "account": "601000", "libelle": "Charge", "debitFcfa": %d },
                            { "account": "401000", "libelle": "Dette", "creditFcfa": %d }
                          ] }
                        """.formatted(today, amount, amount))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    @Test
    void the_dashboard_holds_once_the_journal_is_not_empty() {
        UserEntity admin = tenantAdmin();

        // À vide, rien à agréger : c'est le cas qui passait déjà.
        givenAs(admin).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200)
                .body("data", notNullValue());

        postEntry(admin, 50000);
        postEntry(admin, 25000);

        givenAs(admin).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200)
                .body("data", notNullValue());
    }

    @Test
    void the_chart_reports_the_movements_of_each_account() {
        UserEntity admin = tenantAdmin();
        postEntry(admin, 40000);

        givenAs(admin).queryParam("family", "CHARGES")
                .when().get("/api/v1/accounting/chart")
                .then().statusCode(200)
                .body("data.find { it.number == '601000' }.movementsCount", equalTo(1))
                .body("data.find { it.number == '601000' }.balanceFcfa", equalTo(40000F));
    }
}
