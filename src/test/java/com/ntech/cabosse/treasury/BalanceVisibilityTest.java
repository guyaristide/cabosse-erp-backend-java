package com.ntech.cabosse.treasury;

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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui voit le solde de quel compte de trésorerie (demande expert du
 * 05/09/2026).
 *
 * <p>« Que le PCA seulement et le directeur puissent avoir accès au solde
 * de la banque. Le comptable et la caissière peuvent avoir accès
 * uniquement au solde de la caisse que chacun gère. » Traduit sans rôle ni
 * filière en dur : un droit « voir tous les soldes » à assembler dans les
 * profils, et des gestionnaires désignés sur chaque caisse. Le compte
 * reste listé pour tous, il faut pouvoir le choisir pour un chèque ; seul
 * son solde se masque.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class BalanceVisibilityTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Solde";
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
                "coop-solde-" + TestFixtures.randomSlugSuffix(), "Coopérative Soldes");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        // Sème le plan comptable : la création d'un compte de trésorerie
        // exige que son compte SYSCOHADA existe.
        migrations.runMigrationsFor(tenant.databaseName);
        return user("admin", Roles.TENANT_ADMIN);
    }

    private String createAccount(UserEntity who, String label, String kind,
                                 String syscohada, String managersJson) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "bankName": "%s", "syscohadaAccount": "%s", "label": "%s",
                          "kind": "%s"%s }
                        """.formatted(label, syscohada, label, kind,
                        managersJson == null ? "" : ", \"managerUserIds\": [" + managersJson + "]"))
                .when().post("/api/v1/accounting/bank-accounts").then().statusCode(201)
                .extract().path("data.id");
    }

    private Object balanceOf(JsonPath body, String accountId) {
        return body.get("data.accounts.find { it.id == '%s' }.balance".formatted(accountId));
    }

    @Test
    void the_accounts_page_lists_only_what_the_profile_reaches() {
        UserEntity admin = admin();
        UserEntity cashier = user("caisse", Roles.USER);

        String bank = createAccount(admin, "Banque principale", "BANQUE", "521000", null);
        String ownCash = createAccount(admin, "Caisse gérée", "CAISSE", "571000",
                "\"" + cashier.id + "\"");
        createAccount(admin, "Caisse d'un autre site", "CAISSE", "571000", null);

        String cashierRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Caissière\", \"permissions\": [\"ACCOUNTING_READ\", \"TREASURY_WRITE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(cashierRole))
                .when().put("/api/v1/tenant-roles/users/" + cashier.id).then().statusCode(204);

        // Un à nouveau d'ouverture amorce la caisse : le solde ne vit
        // qu'au journal, la page doit pourtant l'afficher. Daté du jour :
        // les migrations de cette classe posent les périodes comptables,
        // une date d'il y a un an tomberait en période close.
        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"471000\", \"label\": \"Compte d'attente\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        String odId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Solde d'ouverture de caisse",
                          "lines": [
                            { "account": "571000", "libelle": "Espèces en caisse", "debit": 250000 },
                            { "account": "471000", "libelle": "Contrepartie d'amorçage", "credit": 250000 } ] }
                        """.formatted(java.time.LocalDate.now()))
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);

        // La page de tenue des comptes : sa caisse seulement (10/09/2026),
        // avec son solde réel reconstruit du journal, pas un zéro figé.
        JsonPath page = givenAs(cashier)
                .when().get("/api/v1/accounting/bank-accounts?accessibleOnly=true")
                .then().statusCode(200).extract().jsonPath();
        assertThat(page.getList("data.id")).containsExactly(ownCash);
        assertThat(new java.math.BigDecimal(page.getString("data[0].balance")))
                .isEqualByComparingTo("250000");

        // Les sélecteurs de règlement gardent la liste complète : il faut
        // pouvoir désigner où l'argent passe, y compris la banque.
        JsonPath pickers = givenAs(cashier)
                .when().get("/api/v1/accounting/bank-accounts")
                .then().statusCode(200).extract().jsonPath();
        assertThat(pickers.getList("data.id")).contains(bank, ownCash);

        // L'administrateur voit tout, même filtré : il porte le droit.
        JsonPath all = givenAs(admin)
                .when().get("/api/v1/accounting/bank-accounts?accessibleOnly=true")
                .then().statusCode(200).extract().jsonPath();
        assertThat(all.getList("data.id")).hasSize(3);

        // La campagne du bandeau est du contexte ambiant, pas une lecture
        // de référentiel : elle se lit sans le droit référentiel.
        givenAs(cashier).when().get("/api/v1/campaigns/current")
                .then().statusCode(200);
    }

    @Test
    void the_balance_shows_only_to_the_right_or_to_the_manager() {
        UserEntity admin = admin();
        UserEntity cashier = user("caisse", Roles.USER);
        UserEntity director = user("direction", Roles.USER);

        // Trois comptes : la banque, la caisse de la caissière, une autre.
        String bank = createAccount(admin, "Banque principale", "BANQUE", "521000", null);
        String ownCash = createAccount(admin, "Caisse gérée", "CAISSE", "571000",
                "\"" + cashier.id + "\"");
        String otherCash = createAccount(admin, "Caisse d'un autre site", "CAISSE", "571000", null);

        // La caissière : lecture comptable et tenue de caisse, sans le
        // droit de voir tous les soldes. Le directeur : le droit en plus.
        String cashierRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Caissière\", \"permissions\": [\"ACCOUNTING_READ\", \"TREASURY_WRITE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        String directorRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Direction\", \"permissions\": [\"ACCOUNTING_READ\", \"TREASURY_BALANCE_ALL\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(cashierRole))
                .when().put("/api/v1/tenant-roles/users/" + cashier.id).then().statusCode(204);
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(directorRole))
                .when().put("/api/v1/tenant-roles/users/" + director.id).then().statusCode(204);

        // ─── La caissière : sa caisse seulement ───
        JsonPath asCashier = givenAs(cashier).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asCashier, ownCash)).isNotNull();
        assertThat(balanceOf(asCashier, bank)).isNull();
        assertThat(balanceOf(asCashier, otherCash)).isNull();

        // Le point de caisse suit la même règle que l'affichage.
        givenAs(cashier).when().get("/api/v1/treasury/cash-position?accountId=" + ownCash)
                .then().statusCode(200);
        givenAs(cashier).when().get("/api/v1/treasury/cash-position?accountId=" + bank)
                .then().statusCode(403);

        // ─── Le droit « tous les soldes » ouvre tout, banque comprise ───
        JsonPath asDirector = givenAs(director).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asDirector, bank)).isNotNull();
        assertThat(balanceOf(asDirector, otherCash)).isNotNull();

        // L'administrateur du tenant détient tous les droits, donc tout voit.
        JsonPath asAdmin = givenAs(admin).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asAdmin, bank)).isNotNull();

        // ─── Les portées intermédiaires : toutes les caisses sans la
        // banque, ou la banque sans les caisses ───
        UserEntity headCashier = user("caisses", Roles.USER);
        UserEntity finance = user("banques", Roles.USER);
        String cashRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Caisse centrale\", \"permissions\": [\"ACCOUNTING_READ\", \"TREASURY_CASH_BALANCE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        String bankRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Suivi bancaire\", \"permissions\": [\"ACCOUNTING_READ\", \"TREASURY_BANK_BALANCE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(cashRole))
                .when().put("/api/v1/tenant-roles/users/" + headCashier.id).then().statusCode(204);
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(bankRole))
                .when().put("/api/v1/tenant-roles/users/" + finance.id).then().statusCode(204);

        JsonPath asHeadCashier = givenAs(headCashier).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asHeadCashier, ownCash)).isNotNull();
        assertThat(balanceOf(asHeadCashier, otherCash)).isNotNull();
        assertThat(balanceOf(asHeadCashier, bank)).isNull();

        JsonPath asFinance = givenAs(finance).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asFinance, bank)).isNotNull();
        assertThat(balanceOf(asFinance, ownCash)).isNull();
        assertThat(balanceOf(asFinance, otherCash)).isNull();

        // ─── La désignation nominative vaut aussi pour une banque : le
        // comptable rattaché à ce compte le lit, sans droit de portée et
        // sans voir les autres comptes ───
        UserEntity accountant = user("comptable", Roles.USER);
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(cashierRole))
                .when().put("/api/v1/tenant-roles/users/" + accountant.id).then().statusCode(204);
        String managedBank = createAccount(admin, "Banque suivie", "BANQUE", "521000",
                "\"" + accountant.id + "\"");
        JsonPath asAccountant = givenAs(accountant).when().get("/api/v1/accounting/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(balanceOf(asAccountant, managedBank)).isNotNull();
        assertThat(balanceOf(asAccountant, bank)).isNull();
        assertThat(balanceOf(asAccountant, otherCash)).isNull();
    }
}
