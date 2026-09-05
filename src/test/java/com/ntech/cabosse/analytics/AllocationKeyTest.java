package com.ntech.cabosse.analytics;

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

/**
 * Clés de répartition analytique (backlog CPT-17) : référentiel éditable et
 * ventilation d'une charge indirecte (dépense directe) sur plusieurs centres
 * de coût au prorata des poids.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AllocationKeyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rep-" + TestFixtures.randomSlugSuffix(), "Coopérative Répartition");
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
        // Une caisse ne peut jamais être négative : la structure y met
        // son solde d'ouverture avant toute sortie d'espèces.
        fundCashBox(u, 50_000_000);
        return u;
    }

    private void createCostCenter(UserEntity admin, String code, String name) {
        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/cost-centers").then().statusCode(201);
    }

    @Test
    void allocation_key_referential_crud() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "COL", "Collecte");
        createCostCenter(admin, "ADM", "Administration");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "STRUCT", "name": "Frais de structure", "method": "par effectifs",
                          "lines": [ { "costCenter": "COL", "weight": 60 },
                                     { "costCenter": "ADM", "weight": 40 } ] }
                        """)
                .when().post("/api/v1/allocation-keys").then().statusCode(201)
                .body("data.code", equalTo("STRUCT"))
                .body("data.lines.size()", equalTo(2));

        givenAs(admin).when().get("/api/v1/allocation-keys")
                .then().statusCode(200)
                .body("data.find { it.code == 'STRUCT' }.method", equalTo("par effectifs"));
    }

    @Test
    void indirect_expense_is_split_across_cost_centers() {
        UserEntity admin = tenantAdmin();
        createCostCenter(admin, "COL", "Collecte");
        createCostCenter(admin, "AGRO", "Agronomie");
        createCostCenter(admin, "ADM", "Administration");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "STRUCT", "name": "Frais de structure",
                          "lines": [ { "costCenter": "COL", "weight": 50 },
                                     { "costCenter": "AGRO", "weight": 30 },
                                     { "costCenter": "ADM", "weight": 20 } ] }
                        """)
                .when().post("/api/v1/allocation-keys").then().statusCode(201);

        // Dépense indirecte de 100 000 ventilée 50/30/20 sur COL/AGRO/ADM.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "chargeAccount": "628000", "label": "Gardiennage",
                          "amountHt": 100000, "paymentMethod": "CASH",
                          "allocationKeyCode": "STRUCT", "expenseDate": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/direct-expenses").then().statusCode(201)
                .body("data.allocationKeyName", equalTo("Frais de structure"));

        // La pièce porte 3 lignes de charge imputées, + le crédit caisse.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                // L'amorçage de la caisse compte pour une pièce.
                .body("data.total", equalTo(2))
                .body("data.items[0].entries.find { it.costCenter == 'COL' }.debit", equalTo(50000))
                .body("data.items[0].entries.find { it.costCenter == 'AGRO' }.debit", equalTo(30000))
                .body("data.items[0].entries.find { it.costCenter == 'ADM' }.debit", equalTo(20000))
                .body("data.items[0].entries.find { it.syscohadaAccount == '571000' }.credit",
                        equalTo(100000));
    }
}
