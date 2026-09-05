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
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le relevé interne d'un compte de trésorerie.
 *
 * <p>Énoncé par l'utilisateur comme grille de lecture financière : « la
 * section banque constate toute opération qui s'est conclue par un
 * encaissement ou un décaissement par chèque ». Cette lecture n'existait
 * nulle part : le rapprochement confronte à un document de la banque,
 * l'état des flux agrège par période, et aucun écran ne répondait à
 * « qu'est-il sorti de ce compte, et pourquoi ».</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AccountStatementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-rel-" + TestFixtures.randomSlugSuffix(), "Coopérative Relevé");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Relevé";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 500_000_000);
        return u;
    }

    private String bankAccount(UserEntity who, String label, String chart) {
        // Le compte du plan d'abord : un compte de trésorerie s'impute sur
        // un compte qui existe, et le serveur refuse le contraire.
        givenAs(who).contentType("application/json")
                .body("""
                        { "number": "%s", "label": "%s", "family": "TRESORERIE" }
                        """.formatted(chart, label))
                .when().post("/api/v1/accounting/chart").then();
        return givenAs(who).contentType("application/json")
                .body("""
                        { "kind": "BANQUE", "label": "%s",
                          "syscohadaAccount": "%s", "bankName": "%s" }
                        """.formatted(label, chart, label))
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");
    }

    private String openCampaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKg": 900 }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private String delegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    /** Une avance décaissée par chèque, avec les frais que la banque prélève. */
    private void disburseByCheque(UserEntity who, String campaignId, String delegateName,
                                  String bankAccountId, long amount, long fees) {
        String delegateId = delegate(who, delegateName);
        String id = givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CHEQUE",
                          "campaignId": "%s" }
                        """.formatted(delegateId, LocalDate.now(), amount, campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(who).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(who).contentType("application/json")
                .body("""
                        { "paymentMethod": "CHEQUE", "bankAccountId": "%s",
                          "paymentRef": "CHQ-001", "bankFees": %d }
                        """.formatted(bankAccountId, fees))
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
    }

    private io.restassured.response.ValidatableResponse statement(
            UserEntity who, String accountId, String query) {
        return givenAs(who).when()
                .get("/api/v1/treasury/accounts/" + accountId + "/statement" + query)
                .then().statusCode(200);
    }

    // ─── Ce que le relevé montre ────────────────────────────────────

    @Test
    void every_movement_names_the_operation_that_produced_it() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Abidjan", "521100");
        disburseByCheque(admin, campaign, "KONE Adama", bank, 2_000_000, 0);

        var response = statement(admin, bank, "");
        List<String> sources = response.extract().path("data.page.items.sourceType");
        // Un relevé qui ne dirait que « sortie 2 000 000 » n'expliquerait
        // rien : la question est au titre de quoi.
        assertThat(sources).contains("COLLECTOR_ADVANCE");
        List<String> ids = response.extract().path("data.page.items.sourceId");
        assertThat(ids.get(0)).isNotNull();
    }

    @Test
    void bank_charges_stand_on_their_own_line() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Frais", "521200");
        disburseByCheque(admin, campaign, "YAO Brou", bank, 2_000_000, 15_000);

        // La banque les débite séparément : fondus dans le décaissement,
        // ils ne se rapprocheraient d'aucune des deux lignes du relevé.
        List<Object> amounts = statement(admin, bank, "").extract()
                .path("data.page.items.amount");
        assertThat(amounts).hasSize(2);
        assertThat(((Number) amounts.get(1)).doubleValue()).isEqualTo(15_000d);
    }

    @Test
    void the_totals_and_the_closing_balance_cover_the_period() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Totaux", "521300");
        disburseByCheque(admin, campaign, "TRAORE Solange", bank, 1_000_000, 20_000);

        var response = statement(admin, bank, "?from=" + LocalDate.now().minusDays(1)
                + "&to=" + LocalDate.now());
        Number out = response.extract().path("data.totalOut");
        assertThat(out.doubleValue()).isEqualTo(1_020_000d);
        Number closing = response.extract().path("data.closingBalance");
        // Rien n'est entré, tout est sorti : le compte est à découvert,
        // ce qu'un compte de banque a le droit d'être.
        assertThat(closing.doubleValue()).isEqualTo(-1_020_000d);
    }

    // ─── Le filtre de sens ──────────────────────────────────────────

    @Test
    void the_statement_narrows_to_outflows() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Sens", "521400");
        disburseByCheque(admin, campaign, "DIABATE Moussa", bank, 800_000, 0);

        Number outCount = statement(admin, bank, "?direction=OUT")
                .extract().path("data.page.total");
        assertThat(outCount.intValue()).isEqualTo(1);

        Number inCount = statement(admin, bank, "?direction=IN")
                .extract().path("data.page.total");
        assertThat(inCount.intValue()).isEqualTo(0);
    }

    @Test
    void the_opening_balance_ignores_the_direction_filter() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Ouverture", "521500");
        disburseByCheque(admin, campaign, "OUATTARA Salif", bank, 600_000, 0);

        // Un solde n'est pas la somme des seules entrées. Le filtrer par
        // sens ferait apparaître le compte plus riche qu'il n'est.
        Number opening = statement(admin, bank,
                "?from=" + LocalDate.now().plusDays(1) + "&direction=IN")
                .extract().path("data.openingBalance");
        assertThat(opening.doubleValue()).isEqualTo(-600_000d);
    }

    // ─── Le solde courant ───────────────────────────────────────────

    @Test
    void the_running_balance_carries_over_to_the_next_page() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Pages", "521600");
        disburseByCheque(admin, campaign, "BAMBA Yacouba", bank, 500_000, 30_000);

        // Deux lignes, une par page. Repartir du solde d'ouverture de la
        // période sur la seconde page rendrait une colonne fausse.
        Number second = statement(admin, bank, "?perPage=1&page=1")
                .extract().path("data.page.items[0].balance");
        assertThat(second.doubleValue()).isEqualTo(-530_000d);
    }

    // ─── Ce que le relevé avoue ─────────────────────────────────────

    @Test
    void a_shared_chart_account_is_named_instead_of_being_hidden() {
        UserEntity admin = admin();
        String first = bankAccount(admin, "Caisse Méagui", "521700");
        bankAccount(admin, "Caisse Soubré", "521700");

        // Rien n'impose qu'un compte de trésorerie ait son propre compte
        // comptable. Quand deux le partagent, leurs mouvements sont
        // indiscernables : mieux vaut le dire que laisser croire que le
        // relevé ne montre qu'un tiroir.
        List<String> shared = statement(admin, first, "").extract().path("data.sharedWith");
        assertThat(shared).containsExactly("Caisse Soubré");
    }

    @Test
    void the_export_covers_the_period_and_not_the_page() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String bank = bankAccount(admin, "Banque Export", "521900");
        disburseByCheque(admin, campaign, "COULIBALY Awa", bank, 900_000, 10_000);

        // Deux lignes, une seule par page à l'écran : un export borné à la
        // page ne servirait à rien, on exporte pour retravailler l'ensemble.
        String csv = givenAs(admin).when()
                .get("/api/v1/treasury/accounts/" + bank + "/statement/export?format=csv")
                .then().statusCode(200).extract().asString();
        // La référence des demandes d'avance est passée de « AV- » à
        // « DA-DEL- » le 03/09/2026, à la demande de l'expert.
        long dataLines = csv.lines().filter(l -> l.contains("DA-DEL-")).count();
        assertThat(dataLines).isEqualTo(2);
        assertThat(csv).contains("Frais bancaires");
    }

    @Test
    void one_organization_never_reads_the_account_of_another() {
        UserEntity first = admin();
        String bank = bankAccount(first, "Banque Étanche", "521800");

        UserEntity other = admin();
        givenAs(other).when().get("/api/v1/treasury/accounts/" + bank + "/statement")
                .then().statusCode(404);
    }
}
