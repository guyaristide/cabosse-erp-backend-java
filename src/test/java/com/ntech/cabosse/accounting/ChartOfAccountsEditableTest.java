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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Le plan comptable appartient au tenant.
 *
 * <p>Il était semé et étendu par migrations : ouvrir une deuxième caisse
 * ou une deuxième banque demandait une livraison. La règle « une caisse ne
 * se garnit que depuis une banque » n'a pourtant de sens que si la
 * structure peut déclarer ses caisses, et une coopérative à plusieurs
 * sites en a plusieurs dès le premier jour.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ChartOfAccountsEditableTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    // Sans migrations, le plan comptable du tenant est vide : le test
    // vérifierait alors des règles sur un socle absent.
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-plan-" + TestFixtures.randomSlugSuffix(), "Coopérative Plan");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
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

    private io.restassured.response.ValidatableResponse createAccount(
            UserEntity admin, String number, String label, String family) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "number": "%s", "label": "%s", "family": "%s" }
                        """.formatted(number, label, family))
                .when().post("/api/v1/accounting/chart").then();
    }

    @Test
    void a_cooperative_opens_its_own_accounts() {
        UserEntity admin = tenantAdmin();

        String id = createAccount(admin, "571100", "Caisse Bangolo", "TRESORERIE")
                .statusCode(201)
                .body("data.number", equalTo("571100"))
                .body("data.system", equalTo(false))
                .extract().path("data.id");

        givenAs(admin).when().get("/api/v1/accounting/chart")
                .then().statusCode(200).body("data.number", hasItem("571100"));

        // Et le compte sert immédiatement : c'est tout l'objet.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CAISSE", "label": "Caisse Bangolo",
                          "syscohadaAccount": "571100", "bankName": "Caisse Bangolo" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts").then().statusCode(201);

        // Renommer se peut.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "number": "571100", "label": "Caisse de Bangolo", "family": "TRESORERIE" }
                        """)
                .when().put("/api/v1/accounting/chart/" + id)
                .then().statusCode(200).body("data.label", equalTo("Caisse de Bangolo"));
    }

    @Test
    void a_number_already_taken_is_refused() {
        UserEntity admin = tenantAdmin();
        createAccount(admin, "571100", "Caisse Bangolo", "TRESORERIE").statusCode(201);
        createAccount(admin, "571100", "Autre caisse", "TRESORERIE").statusCode(422);
    }

    @Test
    void a_number_is_never_changed_because_past_entries_carry_it() {
        UserEntity admin = tenantAdmin();
        String id = createAccount(admin, "571100", "Caisse Bangolo", "TRESORERIE")
                .statusCode(201).extract().path("data.id");

        // Renuméroter détacherait les écritures déjà passées de leur
        // compte, sans que rien ne le signale.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "number": "571900", "label": "Caisse Bangolo", "family": "TRESORERIE" }
                        """)
                .when().put("/api/v1/accounting/chart/" + id)
                .then().statusCode(422)
                .body("statusMessage", containsString("ne se change pas"));
    }

    @Test
    void a_core_account_cannot_be_deactivated() {
        UserEntity admin = tenantAdmin();
        // Le premier compte du socle venu : ce qui compte est le drapeau,
        // pas le numéro, que les migrations ont pu normaliser.
        String caisseId = givenAs(admin).when().get("/api/v1/accounting/chart")
                .then().statusCode(200)
                .extract().path("data.find { it.system == true }.id");

        // Le moteur l'emploie sans le choisir : le retirer ferait échouer
        // un paiement au moment de passer l'écriture.
        givenAs(admin).when()
                .patch("/api/v1/accounting/chart/" + caisseId + "/active?value=false")
                .then().statusCode(422)
                .body("statusMessage", containsString("socle"));
    }

    @Test
    void an_account_opened_by_the_structure_can_be_put_aside() {
        UserEntity admin = tenantAdmin();
        String id = createAccount(admin, "571100", "Caisse provisoire", "TRESORERIE")
                .statusCode(201).extract().path("data.id");

        givenAs(admin).when()
                .patch("/api/v1/accounting/chart/" + id + "/active?value=false")
                .then().statusCode(200).body("data.active", equalTo(false));

        // Désactivé, pas supprimé : un exercice antérieur reste lisible.
        givenAs(admin).when().get("/api/v1/accounting/chart")
                .then().statusCode(200).body("data.number", hasItem("571100"));
    }

    @Test
    void a_malformed_number_is_refused() {
        UserEntity admin = tenantAdmin();
        createAccount(admin, "57", "Trop court", "TRESORERIE").statusCode(400);
        createAccount(admin, "571 100", "Avec espace", "TRESORERIE").statusCode(400);
        createAccount(admin, "57A100", "Avec lettre", "TRESORERIE").statusCode(400);
    }

    /**
     * Le plan éditable sert la spécification Trésorerie : une deuxième
     * caisse déclarée obéit aux mêmes règles que la première.
     */
    @Test
    void a_second_cash_box_obeys_the_same_rules() {
        UserEntity admin = tenantAdmin();
        createAccount(admin, "571100", "Caisse Bangolo", "TRESORERIE").statusCode(201);

        String bank = givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "BANQUE", "label": "Banque Abidjan",
                          "syscohadaAccount": "521000", "bankName": "Banque Abidjan" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");
        String cashA = givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CAISSE", "label": "Caisse centrale",
                          "syscohadaAccount": "571000", "bankName": "Caisse centrale" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");
        String cashB = givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CAISSE", "label": "Caisse Bangolo",
                          "syscohadaAccount": "571100", "bankName": "Caisse Bangolo" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");

        // De caisse à caisse : refusé, comme pour la première.
        transfer(admin, cashA, cashB, 50000).statusCode(422);
        // Depuis la banque : accepté.
        transfer(admin, bank, cashB, 50000).statusCode(201);
        // Vide, elle ne verse rien : le garde connaît le nouveau compte.
        transfer(admin, cashB, bank, 50000).statusCode(422);

        givenAs(admin).when().get("/api/v1/accounting/chart")
                .then().statusCode(200)
                .body("data.findAll { it.active == false }.number", not(hasItem("571100")));
    }

    private io.restassured.response.ValidatableResponse transfer(
            UserEntity admin, String fromId, String toId, int amount) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "fromAccountId": "%s", "toAccountId": "%s", "amountFcfa": %d,
                          "sentAt": "%s", "carrierName": "Chauffeur Koffi" }
                        """.formatted(fromId, toId, amount, LocalDate.now()))
                .when().post("/api/v1/treasury/transfers").then();
    }
}
