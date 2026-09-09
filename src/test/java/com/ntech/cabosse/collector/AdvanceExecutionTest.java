package com.ntech.cabosse.collector;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * La transmission à l'exécution (matrice expert du 07/09/2026).
 *
 * <p>Le comptable constate l'avance approuvée et « saisit exécuter » vers
 * la caissière : horodatage sur le dossier, mise en évidence dans la file
 * des décaissements, notification à ceux qui décaissent. Signal non
 * bloquant : une avance jamais transmise se décaisse quand même, le
 * système constate et ne barre pas la route.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceExecutionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Exécution";
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
                "coop-execution-" + TestFixtures.randomSlugSuffix(), "Coopérative Exécution");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = user("admin", Roles.TENANT_ADMIN);
        fundCashBox(u, 5_000_000);
        return u;
    }

    private int unreadOf(UserEntity who) {
        return ((Number) givenAs(who).when().get("/api/v1/notifications/inbox/unread-count")
                .then().statusCode(200).extract().path("data.unread")).intValue();
    }

    @Test
    void the_accountant_hands_over_and_the_cashier_is_told_without_any_gate() {
        UserEntity admin = admin();
        UserEntity cashier = user("caissiere", Roles.USER);
        String cashierRole = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Caisse exécution\", \"permissions\": "
                        + "[\"COLLECTION_READ\", \"COLLECTION_ADVANCE_DISBURSE\", \"MEMBER_CREDIT_DISBURSE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(cashierRole))
                .when().put("/api/v1/tenant-roles/users/" + cashier.id).then().statusCode(204);

        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Exécution\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // ─── Avance approuvée, transmise à l'exécution ───
        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 400000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200);
        // L'approbation a déjà prévenu ceux qui décaissent : on repart de
        // ce compteur pour isoler la notification de transmission.
        int before = unreadOf(cashier);

        givenAs(admin).when()
                .post("/api/v1/collector-advances/" + advanceId + "/request-execution")
                .then().statusCode(200)
                .body("data.executionRequestedAt", notNullValue())
                .body("data.executionRequestedByEmail", equalTo(admin.email));

        assertThat(unreadOf(cashier)).isEqualTo(before + 1);

        // La caissière désigne la caisse ou la banque au décaissement :
        // la liste des comptes (sans soldes) s'ouvre à son droit de
        // décaisser, sans lecture comptable générale ni rattachement de
        // gestionnaire au compte choisi.
        givenAs(cashier).when().get("/api/v1/accounting/bank-accounts")
                .then().statusCode(200);
        List<Map<String, Object>> inbox = givenAs(cashier)
                .when().get("/api/v1/notifications/inbox")
                .then().statusCode(200).extract().path("data.items");
        assertThat(inbox.get(0).get("eventType"))
                .isEqualTo("collector-advance.execution-requested");

        // La file des décaissements porte la transmission, en évidence.
        List<Map<String, Object>> payables = givenAs(admin)
                .when().get("/api/v1/treasury/payables?kind=COLLECTOR_ADVANCE")
                .then().statusCode(200).extract().path("data.page.items");
        assertThat(payables.stream()
                .filter(p -> advanceId.equals(String.valueOf(p.get("sourceId"))))
                .findFirst().orElseThrow()
                .get("executionRequestedAt")).isNotNull();

        // ─── Non bloquant : une avance jamais transmise se décaisse ───
        String direct = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 100000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + direct + "/approve")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/collector-advances/" + direct + "/disburse")
                .then().statusCode(200);

        // ─── Le même geste côté crédit producteur ───
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Assi\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String creditId = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "CREDIT", "amount": 150000, "purpose": "Toiture" }
                        """.formatted(memberId))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
        String status = givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200).extract().path("data.status");
        if ("PENDING_APPROVAL".equals(status)) {
            givenAs(admin).contentType("application/json").body("{\"note\":\"Accord\"}")
                    .when().post("/api/v1/member-credits/" + creditId + "/approve")
                    .then().statusCode(200);
        }
        int beforeCredit = unreadOf(cashier);
        givenAs(admin).when()
                .post("/api/v1/member-credits/" + creditId + "/request-execution")
                .then().statusCode(200)
                .body("data.executionRequestedAt", notNullValue());
        assertThat(unreadOf(cashier)).isEqualTo(beforeCredit + 1);
    }
}
