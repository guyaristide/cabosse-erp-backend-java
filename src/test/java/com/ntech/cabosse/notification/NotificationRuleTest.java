package com.ntech.cabosse.notification;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Le moteur de réglage des notifications (CE-205).
 *
 * <p>L'administrateur décide quels événements déclenchent, vers quels
 * profils, par quels canaux (au moins un) et avec quelles copies. Un
 * événement sans règle garde son comportement d'origine ; la règle est
 * l'exception, pas une recopie du défaut.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class NotificationRuleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Règle";
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
                "coop-regle-" + TestFixtures.randomSlugSuffix(), "Coopérative Règles");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    private String createRole(UserEntity admin, String name, String... permissions) {
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(name, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
    }

    private void assign(UserEntity admin, UserEntity target, String roleId) {
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + target.id).then().statusCode(204);
    }

    private String requestAdvance(UserEntity who, String delegateId, int amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.ref");
    }

    private int unreadOf(UserEntity who) {
        return ((Number) givenAs(who).when().get("/api/v1/notifications/inbox/unread-count")
                .then().statusCode(200).extract().path("data.unread")).intValue();
    }

    @Test
    void the_rule_decides_who_hears_what_and_through_which_channel() {
        UserEntity admin = admin();
        UserEntity approver = user("approbateur", Roles.USER);
        UserEntity governance = user("gouvernance", Roles.USER);
        String approverRole = createRole(admin, "Approbation",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");
        String governanceRole = createRole(admin, "Gouvernance", "COLLECTION_READ");
        assign(admin, approver, approverRole);
        assign(admin, governance, governanceRole);
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Règles\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // ─── Le catalogue et ses défauts ───
        givenAs(admin).when().get("/api/v1/notifications/rules")
                .then().statusCode(200)
                .body("data", hasSize(8))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.enabled",
                        equalTo(true))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.channels",
                        hasItem("EMAIL"))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.channels",
                        hasItem("IN_APP"))
                .body("data.find { it.eventCode == 'advance-refund.reported' }.audienceConfigurable",
                        equalTo(false));

        // ─── Un événement actif exige au moins un canal ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(422);

        // ─── Application seule : le courriel se tait ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"IN_APP\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref1 = requestAdvance(admin, delegateId, 100_000);
        assertThat(unreadOf(approver)).isEqualTo(1);
        List<Map<String, Object>> emails = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails.stream().anyMatch(d -> ref1.equals(d.get("subjectRef")))).isFalse();

        // ─── Les profils remplacent l'audience par défaut ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"IN_APP\"], \"recipientRoleIds\": [\"%s\"] }"
                        .formatted(governanceRole))
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        requestAdvance(admin, delegateId, 150_000);
        assertThat(unreadOf(governance)).isEqualTo(1);
        // L'approbateur n'est plus dans l'audience : son compteur ne bouge pas.
        assertThat(unreadOf(approver)).isEqualTo(1);

        // ─── Les copies partent par courriel ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\", \"IN_APP\"], "
                        + "\"recipientRoleIds\": [\"%s\"], \"ccEmails\": [\"controle@exemple.ci\"] }"
                        .formatted(governanceRole))
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref3 = requestAdvance(admin, delegateId, 200_000);
        List<Map<String, Object>> emails3 = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails3.stream().anyMatch(d ->
                ref3.equals(d.get("subjectRef")) && "controle@exemple.ci".equals(d.get("target"))))
                .isTrue();

        // ─── Éteint, l'événement se tait partout ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": false, \"channels\": [\"EMAIL\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref4 = requestAdvance(admin, delegateId, 250_000);
        // Le scénario des copies a déjà porté la gouvernance à deux.
        assertThat(unreadOf(governance)).isEqualTo(2);
        List<Map<String, Object>> emails4 = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails4.stream().anyMatch(d -> ref4.equals(d.get("subjectRef")))).isFalse();

        // Une adresse en copie invalide est refusée.
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\"], \"ccEmails\": [\"pas-une-adresse\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(422);
    }
}
