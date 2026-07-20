package com.ntech.cabosse.expense;

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
 * Dépenses directes sans bon de livraison (backlog ACH-03) : petite caisse
 * (charge / caisse) et contrat/abonnement (charge + TVA déductible / banque),
 * comptabilisées sans réception ni mouvement de stock.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DirectExpenseTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dep-" + TestFixtures.randomSlugSuffix(), "Coopérative Dépenses");
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

    @Test
    void petty_cash_expense_posts_charge_over_cash() {
        UserEntity admin = tenantAdmin();

        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "chargeAccount": "628000",
                          "label": "Fournitures de nettoyage", "amountHtFcfa": 5000,
                          "paymentMethod": "CASH", "expenseDate": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201)
                .body("data.ref", equalTo("DEP-" + LocalDate.now().getYear() + "-0001"))
                .body("data.amountTtcFcfa", equalTo(5000))
                .body("data.treasuryAccount", equalTo("571000"));

        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].sourceType", equalTo("DIRECT_EXPENSE"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("628000"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("571000"));
    }

    @Test
    void contract_expense_with_vat_posts_charge_vat_over_bank() {
        UserEntity admin = tenantAdmin();

        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Compagnie d'électricité\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CONTRACT", "supplierId": "%s", "chargeAccount": "627000",
                          "label": "Électricité", "periodLabel": "Juillet 2026",
                          "amountHtFcfa": 100000, "vatRatePct": 18,
                          "paymentMethod": "BANK_TRANSFER", "expenseDate": "%s" }
                        """.formatted(supplierId, LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201)
                .body("data.vatAmountFcfa", equalTo(18000))
                .body("data.amountTtcFcfa", equalTo(118000))
                .body("data.treasuryAccount", equalTo("521000"));

        // Débit 627000 (HT) + 445660 (TVA) / crédit 521000 (TTC).
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].entries.syscohadaAccount", hasItem("627000"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("445660"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("521000"));
    }

    @Test
    void expense_requires_a_charge_account() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "label": "Sans compte",
                          "amountHtFcfa": 1000, "paymentMethod": "CASH" }
                        """)
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(422);
    }
}
