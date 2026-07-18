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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Assistant de clôture d'exercice (backlog CPT-12) : arrêté complet
 * (en-cours, impôt, clôture 6/7 vers 13, snapshot) puis affectation
 * différée du résultat. Exercice précédent = année civile écoulée
 * (préférence par défaut : début janvier).
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class FiscalYearResourceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-exercice-" + TestFixtures.randomSlugSuffix(), "Coopérative Exercice");
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

    /** OD validée dans l'exercice précédent, pour peupler le journal. */
    private void postOd(UserEntity admin, LocalDate date, String body) {
        String id = givenAs(admin)
                .contentType("application/json")
                .body(body.formatted(date))
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    private void lockAllMonths(UserEntity admin, int year) {
        for (int month = 1; month <= 12; month++) {
            givenAs(admin)
                    .contentType("application/json")
                    .when().post("/api/v1/accounting/periods/" + YearMonth.of(year, month) + "/lock")
                    .then().statusCode(200);
        }
    }

    @Test
    void full_cycle_arrete_then_allocation() {
        UserEntity admin = tenantAdmin();
        int previousYear = LocalDate.now().getYear() - 1;
        LocalDate midYear = LocalDate.of(previousYear, 6, 15);

        // Une vente (411/701) et une charge (601/401) sur l'exercice écoulé.
        postOd(admin, midYear, """
                { "date": "%s", "libelle": "Vente cacao",
                  "lines": [
                    { "account": "411", "libelle": "Client", "debitFcfa": 500000 },
                    { "account": "701", "libelle": "Vente", "creditFcfa": 500000 }
                  ] }
                """);
        postOd(admin, midYear, """
                { "date": "%s", "libelle": "Achat cacao",
                  "lines": [
                    { "account": "601", "libelle": "Achat", "debitFcfa": 200000 },
                    { "account": "401", "libelle": "Fournisseur", "creditFcfa": 200000 }
                  ] }
                """);

        // Arrêté refusé tant que les mois ne sont pas verrouillés.
        givenAs(admin)
                .contentType("application/json")
                .body("{}")
                .when().post("/api/v1/accounting/fiscal-years/arreter")
                .then().statusCode(422);

        lockAllMonths(admin, previousYear);

        // Preview : résultat avant impôt 300 000, impôt proposé 0 (taux défaut).
        givenAs(admin)
                .when().get("/api/v1/accounting/fiscal-years/preview")
                .then().statusCode(200)
                .body("data.resultBeforeTaxFcfa", equalTo(300000))
                .body("data.proposedTaxFcfa", equalTo(0))
                .body("data.unlockedPeriods", equalTo(java.util.List.of()));

        // Arrêté avec un en-cours de 50 000 et un impôt de 30 000.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "taxFcfa": 30000,
                          "wipLines": [ { "label": "OF tablettes en cours", "amountFcfa": 50000 } ] }
                        """)
                .when().post("/api/v1/accounting/fiscal-years/arreter")
                .then().statusCode(201)
                .body("data.status", equalTo("ARRETE"))
                .body("data.resultBeforeTaxFcfa", equalTo(350000))
                .body("data.taxFcfa", equalTo(30000))
                .body("data.resultNetFcfa", equalTo(320000))
                .body("data.wipTotalFcfa", equalTo(50000))
                .body("data.snapshot.statement", hasItem("BILAN"));

        // 2 OD + en-cours + contre-passation + impôt + 2 clôtures = 7 pièces.
        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(7));

        // Le CR de l'exercice reste lisible : les pièces de clôture sont exclues.
        String yearId = givenAs(admin)
                .when().get("/api/v1/accounting/fiscal-years")
                .then().statusCode(200)
                .body("data[0].label", equalTo(String.valueOf(previousYear)))
                .extract().path("data[0].id");

        // Double arrêté refusé.
        givenAs(admin)
                .contentType("application/json")
                .body("{}")
                .when().post("/api/v1/accounting/fiscal-years/arreter")
                .then().statusCode(422);

        // Affectation : total différent du résultat net → refus.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lines": [ { "account": "121", "amountFcfa": 100000 } ] }
                        """)
                .when().post("/api/v1/accounting/fiscal-years/" + yearId + "/allocate")
                .then().statusCode(422);

        // Affectation valide : 100 000 au capital, 220 000 en report à nouveau.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lines": [
                            { "account": "101", "amountFcfa": 100000 },
                            { "account": "121", "amountFcfa": 220000 } ] }
                        """)
                .when().post("/api/v1/accounting/fiscal-years/" + yearId + "/allocate")
                .then().statusCode(200)
                .body("data.status", equalTo("CLOTURE"));

        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(8));

        // Ré-affectation refusée.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lines": [ { "account": "121", "amountFcfa": 320000 } ] }
                        """)
                .when().post("/api/v1/accounting/fiscal-years/" + yearId + "/allocate")
                .then().statusCode(422);
    }
}
