package com.ntech.cabosse.accounting;

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
import static org.hamcrest.Matchers.notNullValue;

/**
 * Les écritures à nouveau : le bilan d'ouverture de l'exercice (journal
 * AN), conclu avec l'expert le 07/09/2026.
 *
 * <p>Une structure qui arrive sur l'outil déclare ses soldes de départ :
 * capital, emprunts, créances clients, dettes fournisseurs, et surtout
 * les caisses et les banques, dont les soldes se lisent ensuite partout
 * (point de caisse, bandeau, garde de provision) puisque tout se calcule
 * du journal. Le geste porte son propre droit, et un bilan d'ouverture ne
 * connaît que des comptes de bilan.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class OpeningEntryTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Ouverture";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-anouveau-" + TestFixtures.randomSlugSuffix(), "Coopérative À-nouveaux");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        migrations.runMigrationsFor(tenant.databaseName);
        return user("admin", Roles.TENANT_ADMIN);
    }

    private String createOpening(UserEntity who, String linesJson, int expected) {
        var r = givenAs(who).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Écritures à nouveau", "lines": [%s] }
                        """.formatted(LocalDate.now().withDayOfMonth(1), linesJson))
                .when().post("/api/v1/accounting/opening-entries").then().statusCode(expected);
        return expected == 201 ? r.extract().path("data.id") : null;
    }

    @Test
    void the_opening_balance_seeds_treasury_receivables_and_capital() {
        UserEntity admin = admin();

        // Le sous-ensemble du fichier de l'expert, équilibré : capital et
        // fournisseurs au crédit, banque, caisse et clients au débit.
        String id = createOpening(admin, """
                { "account": "521000", "libelle": "À-nouveau - Banque", "debit": 5900000 },
                { "account": "571000", "libelle": "À-nouveau - Caisse", "debit": 320000 },
                { "account": "411000", "libelle": "À-nouveau - Clients", "debit": 4100000 },
                { "account": "101000", "libelle": "À-nouveau - Capital social", "credit": 10000000 },
                { "account": "401000", "libelle": "À-nouveau - Fournisseurs", "credit": 320000 }
                """, 201);
        givenAs(admin).when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200)
                .body("data.status", equalTo("VALIDATED"))
                .body("data.kind", equalTo("AN"))
                .body("data.pieceRef", notNullValue());

        // La pièce est au journal AN, et les soldes en découlent : la
        // trésorerie du tableau de bord lit banque + caisse d'ouverture.
        givenAs(admin).when().get("/api/v1/accounting/journal?perPage=5")
                .then().statusCode(200)
                .body("data.items[0].sourceType", equalTo("OPENING_BALANCE"))
                .body("data.items[0].sourceRef", equalTo("AN"));
        var kpis = givenAs(admin).when().get("/api/v1/executive-dashboard?period=year")
                .then().statusCode(200).extract().jsonPath();
        Number cash = kpis.get("data.kpis.find { it.key == 'cash' }.current");
        org.assertj.core.api.Assertions.assertThat(cash.doubleValue()).isEqualTo(6_220_000d);
    }

    @Test
    void an_opening_balance_only_carries_balance_sheet_accounts() {
        UserEntity admin = admin();
        String id = createOpening(admin, """
                { "account": "601000", "libelle": "Achats", "debit": 100000 },
                { "account": "101000", "libelle": "Capital", "credit": 100000 }
                """, 201);
        givenAs(admin).when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(422)
                .body("statusMessage", containsString("601000"));
    }

    @Test
    void the_gesture_has_its_own_right() {
        UserEntity admin = admin();
        UserEntity accountant = user("comptable", Roles.USER);
        String writerRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Comptable courant\", \"permissions\": [\"ACCOUNTING_READ\", \"ACCOUNTING_WRITE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(writerRole))
                .when().put("/api/v1/tenant-roles/users/" + accountant.id).then().statusCode(204);

        // L'écriture courante ne suffit pas : ni pour créer un AN...
        givenAs(accountant).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Essai", "lines": [
                          { "account": "571000", "libelle": "Caisse", "debit": 1000 },
                          { "account": "101000", "libelle": "Capital", "credit": 1000 } ] }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/accounting/opening-entries").then().statusCode(403);

        // ...ni pour valider celui qu'un autre a préparé.
        String id = createOpening(admin, """
                { "account": "571000", "libelle": "Caisse", "debit": 1000 },
                { "account": "101000", "libelle": "Capital", "credit": 1000 }
                """, 201);
        givenAs(accountant).when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(403);

        // Le détenteur du droit, lui, valide par le chemin des à-nouveaux,
        // sans avoir besoin du rôle d'administrateur des OD.
        UserEntity opener = user("ouverture", Roles.USER);
        String openerRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Bilan d'ouverture\", \"permissions\": [\"ACCOUNTING_READ\", \"ACCOUNTING_OPENING_WRITE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(openerRole))
                .when().put("/api/v1/tenant-roles/users/" + opener.id).then().statusCode(204);
        givenAs(opener).when().post("/api/v1/accounting/opening-entries/" + id + "/validate")
                .then().statusCode(200)
                .body("data.status", equalTo("VALIDATED"));
    }
}
