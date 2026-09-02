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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * L'état de ce qui a été réglé.
 *
 * <p>La file « à payer » montre ce qui attend et fait disparaître la ligne
 * une fois payée : elle ne dit jamais ce qui a été fait, ni par qui. La
 * caisse tient en face un tableau de suivi, avec la date du chèque, son
 * montant et le nom de la caissière.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SettlementReportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-regl-" + TestFixtures.randomSlugSuffix(), "Coopérative Règlements");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity admin = user(Roles.TENANT_ADMIN);
        fundCashBox(admin, 50_000_000);
        return admin;
    }

    private UserEntity user(String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "caisse-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Mariam";
        u.lastName = "DIALLO";
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

    private String createDelegate(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    /** Une avance déposée, approuvée et décaissée par chèque. */
    private String settledAdvance(UserEntity admin, String delegateId, int asked, int granted) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), asked))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmountFcfa\": %d }".formatted(granted))
                .when().post("/api/v1/collector-advances/" + id + "/approve").then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("""
                        { "paymentMethod": "BANK_TRANSFER", "paymentRef": "CHQ-0041207",
                          "bankFeesFcfa": 2500 }
                        """)
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
        return id;
    }

    @Test
    void it_says_what_was_paid_and_by_whom() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Gnahoré");
        String id = settledAdvance(admin, delegateId, 3_000_000, 2_000_000);

        givenAs(admin).when().get("/api/v1/treasury/settlements")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].sourceId", equalTo(id))
                .body("data.page.items[0].beneficiaryName", equalTo("Délégué Gnahoré"))
                // Le montant remis est celui qui a été accordé.
                .body("data.page.items[0].amountFcfa", equalTo(2000000))
                .body("data.page.items[0].settledByName", equalTo("Mariam DIALLO"));
    }

    @Test
    void the_settlement_reference_comes_back_as_it_was_typed() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Yeboua");
        settledAdvance(admin, delegateId, 1_000_000, 1_000_000);

        // Un numéro de chèque se recopie sur un talon : le reformater le
        // rendrait introuvable.
        givenAs(admin).when().get("/api/v1/treasury/settlements")
                .then().statusCode(200)
                .body("data.page.items[0].paymentRef", equalTo("CHQ-0041207"));
    }

    @Test
    void the_bank_fees_stay_out_of_what_the_beneficiary_received() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Assamoi");
        settledAdvance(admin, delegateId, 1_000_000, 1_000_000);

        // Les fondre ferait croire que le délégué a touché 997 500.
        givenAs(admin).when().get("/api/v1/treasury/settlements")
                .then().statusCode(200)
                .body("data.page.items[0].amountFcfa", equalTo(1000000))
                .body("data.page.items[0].bankFeesFcfa", equalTo(2500))
                .body("data.totalAmountFcfa", equalTo(1000000))
                .body("data.totalBankFeesFcfa", equalTo(2500));
    }

    @Test
    void what_still_awaits_payment_does_not_appear_here() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Ehui");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 700000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201);

        // L'état dit ce qui est sorti. Une demande en attente n'est pas
        // sortie, et l'y faire figurer gonflerait le décaissé de la
        // période.
        givenAs(admin).when().get("/api/v1/treasury/settlements")
                .then().statusCode(200)
                .body("data.page.items", hasSize(0))
                .body("data.totalAmountFcfa", equalTo(0));
    }

    @Test
    void it_reads_by_beneficiary() {
        UserEntity admin = tenantAdmin();
        String a = createDelegate(admin, "Délégué Kacou");
        String b = createDelegate(admin, "Délégué Zadi");
        settledAdvance(admin, a, 500_000, 500_000);
        settledAdvance(admin, b, 800_000, 800_000);

        givenAs(admin).when().get("/api/v1/treasury/settlements?beneficiaryId=" + b)
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].beneficiaryName", equalTo("Délégué Zadi"))
                .body("data.beneficiaryCount", equalTo(1));
    }

    @Test
    void a_period_outside_the_settlements_returns_an_honest_zero() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Tapé");
        settledAdvance(admin, delegateId, 600_000, 600_000);

        givenAs(admin).when().get("/api/v1/treasury/settlements?from=2020-01-01&to=2020-12-31")
                .then().statusCode(200)
                .body("data.page.items", hasSize(0))
                .body("data.from", equalTo("2020-01-01"))
                .body("data.to", equalTo("2020-12-31"));
    }

    @Test
    void it_exports_because_the_use_described_is_a_tracking_table() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Brou");
        settledAdvance(admin, delegateId, 900_000, 900_000);

        String csv = givenAs(admin).when()
                .get("/api/v1/treasury/settlements/export?format=CSV")
                .then().statusCode(200).extract().asString();

        org.hamcrest.MatcherAssert.assertThat(csv, containsString("Délégué Brou"));
        org.hamcrest.MatcherAssert.assertThat(csv, containsString("CHQ-0041207"));
        org.hamcrest.MatcherAssert.assertThat(csv, containsString("Mariam DIALLO"));
    }
}
