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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Clôture de période comptable (backlog CPT-03) : verrouillage,
 * idempotence, refus d'un mois futur, réouverture motivée et tracée.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AccountingPeriodResourceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-periode-" + TestFixtures.randomSlugSuffix(), "Coopérative Périodes");
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
    void lock_then_list_then_reopen_with_reason() {
        UserEntity admin = tenantAdmin();
        String lastMonth = YearMonth.now().minusMonths(1).toString();

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/lock")
                .then().statusCode(200)
                .body("data.period", equalTo(lastMonth))
                .body("data.status", equalTo("LOCKED"));

        // Idempotent : re-verrouiller ne crée pas de doublon.
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/lock")
                .then().statusCode(200);

        givenAs(admin)
                .when().get("/api/v1/accounting/periods")
                .then().statusCode(200)
                .body("data", hasSize(1));

        // Réouverture sans motif refusée.
        givenAs(admin)
                .contentType("application/json")
                .body("{}")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/reopen")
                .then().statusCode(422);

        givenAs(admin)
                .contentType("application/json")
                .body("{\"reason\":\"Correction facture fournisseur\"}")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/reopen")
                .then().statusCode(200)
                .body("data.status", equalTo("REOPENED"))
                .body("data.reopenReason", equalTo("Correction facture fournisseur"));
    }

    @Test
    void locking_a_future_month_is_rejected() {
        UserEntity admin = tenantAdmin();
        String nextMonth = YearMonth.now().plusMonths(1).toString();
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + nextMonth + "/lock")
                .then().statusCode(422);
    }
}
