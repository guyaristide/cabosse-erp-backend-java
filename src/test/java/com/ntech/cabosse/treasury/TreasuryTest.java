package com.ntech.cabosse.treasury;

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
import static org.hamcrest.Matchers.hasSize;

/**
 * Transports de fonds et point de caisse.
 *
 * <p>La ville de la coopérative n'a pas d'agence bancaire : l'argent part
 * de la banque et voyage en espèces jusqu'à la caisse. Ce que le système
 * doit rendre visible : ce qui est encore en route, ce qui est arrivé
 * amputé, et ce que la caisse devrait contenir quand on l'ouvre.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TreasuryTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-treso-" + TestFixtures.randomSlugSuffix(), "Coopérative Trésorerie");
        // Les comptes de trésorerie s'appuient sur le plan comptable : il
        // faut donc la base du tenant réellement migrée, pas seulement la
        // fiche tenant créée.
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

    private String createAccount(UserEntity admin, String kind, String label, String account) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "%s", "label": "%s", "syscohadaAccount": "%s", "bankName": "%s" }
                        """.formatted(kind, label, account, label))
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String send(UserEntity admin, String fromId, String toId, int amount, String carrier) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "fromAccountId": "%s", "toAccountId": "%s", "amountFcfa": %d,
                          "sentAt": "%s", "carrierName": "%s" }
                        """.formatted(fromId, toId, amount, LocalDate.now(), carrier))
                .when().post("/api/v1/treasury/transfers")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void money_on_the_road_is_neither_in_the_bank_nor_in_the_cash_box() {
        UserEntity admin = tenantAdmin();
        String bank = createAccount(admin, "BANQUE", "Banque Abidjan", "521000");
        String cash = createAccount(admin, "CAISSE", "Caisse centrale", "571000");

        String transferId = send(admin, bank, cash, 5000000, "Chauffeur Koffi");

        givenAs(admin).when().get("/api/v1/treasury/transfers/" + transferId)
                .then().statusCode(200)
                .body("data.status", equalTo("IN_TRANSIT"))
                .body("data.carrierName", equalTo("Chauffeur Koffi"));

        // La caisse ne s'est pas encore garnie, mais elle sait ce qui arrive.
        givenAs(admin).queryParam("accountId", cash)
                .when().get("/api/v1/treasury/cash-position")
                .then().statusCode(200)
                .body("data.theoreticalFcfa", equalTo(0))
                .body("data.inTransitFcfa", equalTo(5000000));

        // La somme est passée par le compte de virements internes.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("585000"));
    }

    @Test
    void a_shortfall_on_arrival_is_recorded_not_hidden() {
        UserEntity admin = tenantAdmin();
        String bank = createAccount(admin, "BANQUE", "Banque Abidjan", "521000");
        String cash = createAccount(admin, "CAISSE", "Caisse centrale", "571000");

        String transferId = send(admin, bank, cash, 1000000, "Chauffeur Koffi");

        // Il manque 20 000 à l'arrivée : la caisse reçoit ce qu'elle a
        // compté, et l'écart est constaté au lieu d'être absorbé.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "amountReceivedFcfa": 980000, "receivedAt": "%s",
                          "notes": "Écart signalé au comptage" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/treasury/transfers/" + transferId + "/receive")
                .then().statusCode(200)
                .body("data.status", equalTo("RECEIVED"))
                .body("data.discrepancyFcfa", equalTo(-20000));

        givenAs(admin).queryParam("accountId", cash)
                .when().get("/api/v1/treasury/cash-position")
                .then().statusCode(200)
                .body("data.theoreticalFcfa", equalTo(980000))
                .body("data.inTransitFcfa", equalTo(0));

        // Le rapprochement du mois nomme la ligne qui ne tombe pas juste.
        givenAs(admin).when().get("/api/v1/treasury/reconciliation")
                .then().statusCode(200)
                .body("data.totalSentFcfa", equalTo(1000000))
                .body("data.totalReceivedFcfa", equalTo(980000))
                .body("data.totalDiscrepancyFcfa", equalTo(-20000))
                .body("data.withDiscrepancy", hasSize(1))
                .body("data.withDiscrepancy[0].carrierName", equalTo("Chauffeur Koffi"));
    }

    @Test
    void the_cash_count_confronts_the_expected_balance() {
        UserEntity admin = tenantAdmin();
        String bank = createAccount(admin, "BANQUE", "Banque Abidjan", "521000");
        String cash = createAccount(admin, "CAISSE", "Caisse centrale", "571000");

        String transferId = send(admin, bank, cash, 2000000, "Chauffeur Koffi");
        givenAs(admin).contentType("application/json")
                .body("{\"amountReceivedFcfa\":2000000,\"receivedAt\":\"" + LocalDate.now() + "\"}")
                .when().post("/api/v1/treasury/transfers/" + transferId + "/receive")
                .then().statusCode(200);

        // Comptage de fin de semaine : il manque 5 000 en caisse.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "accountId": "%s", "countedFcfa": 1995000, "countedAt": "%s" }
                        """.formatted(cash, LocalDate.now()))
                .when().post("/api/v1/treasury/cash-counts")
                .then().statusCode(201)
                .body("data.theoreticalFcfa", equalTo(2000000))
                .body("data.countedFcfa", equalTo(1995000))
                .body("data.discrepancyFcfa", equalTo(-5000))
                // L'écart ne se régularise pas tout seul : il se cherche.
                .body("data.pieceRef", equalTo(null));

        givenAs(admin).queryParam("accountId", cash)
                .when().get("/api/v1/treasury/cash-position")
                .then().statusCode(200)
                .body("data.lastCount.discrepancyFcfa", equalTo(-5000));
    }

    @Test
    void the_discrepancy_can_be_written_off_when_it_is_explained() {
        UserEntity admin = tenantAdmin();
        String bank = createAccount(admin, "BANQUE", "Banque Abidjan", "521000");
        String cash = createAccount(admin, "CAISSE", "Caisse centrale", "571000");

        String transferId = send(admin, bank, cash, 500000, "Chauffeur Koffi");
        givenAs(admin).contentType("application/json")
                .body("{\"amountReceivedFcfa\":500000,\"receivedAt\":\"" + LocalDate.now() + "\"}")
                .when().post("/api/v1/treasury/transfers/" + transferId + "/receive")
                .then().statusCode(200);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "accountId": "%s", "countedFcfa": 497000, "countedAt": "%s",
                          "postAdjustment": true, "notes": "Manquant assumé après recherche" }
                        """.formatted(cash, LocalDate.now()))
                .when().post("/api/v1/treasury/cash-counts")
                .then().statusCode(201)
                .body("data.discrepancyFcfa", equalTo(-3000));

        // Après régularisation, le solde attendu colle au comptage.
        givenAs(admin).queryParam("accountId", cash)
                .when().get("/api/v1/treasury/cash-position")
                .then().statusCode(200)
                .body("data.theoreticalFcfa", equalTo(497000));
    }
}
