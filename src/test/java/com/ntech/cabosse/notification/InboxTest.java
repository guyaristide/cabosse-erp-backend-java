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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * La cloche de l'application (canal IN_APP de la file de notification).
 *
 * <p>Demandé le 06/09/2026 : le moteur n'écrivait qu'aux adresses, la
 * cloche du menu était décorative. Chaque alerte métier a désormais sa
 * jumelle dans l'application, livrée à l'enfilage, strictement
 * personnelle, et le type d'événement porte la navigation vers l'écran
 * concerné.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class InboxTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Cloche";
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
                "coop-cloche-" + TestFixtures.randomSlugSuffix(), "Coopérative Cloche");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    @Test
    void the_bell_carries_the_alert_to_the_right_person_only() {
        UserEntity admin = admin();
        UserEntity approver = user("approbateur", Roles.USER);

        // L'approbateur porte le droit d'approuver, via un profil.
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Approbation\", \"permissions\": [\"COLLECTION_READ\", \"COLLECTION_ADVANCE_APPROVE\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + approver.id).then().statusCode(204);

        // L'administrateur dépose une demande d'avance : l'alerte part
        // vers ceux qui approuvent, déposant exclu.
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Cloche\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        String ref = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 300000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.ref");

        // ─── L'approbateur trouve l'alerte dans sa cloche ───
        givenAs(approver).when().get("/api/v1/notifications/inbox/unread-count")
                .then().statusCode(200).body("data.unread", equalTo(1));
        String notifId = givenAs(approver).when().get("/api/v1/notifications/inbox")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].eventType", equalTo("collector-advance.pending-approval"))
                .body("data.items[0].subjectRef", equalTo(ref))
                .body("data.items[0].readAt", nullValue())
                .extract().path("data.items[0].id");

        // Le déposant n'y voit rien : la boîte est personnelle.
        givenAs(admin).when().get("/api/v1/notifications/inbox")
                .then().statusCode(200).body("data.items", hasSize(0));

        // ─── Lue, elle reste lisible mais ne compte plus ───
        givenAs(approver).contentType("application/json").body("{}")
                .when().post("/api/v1/notifications/inbox/" + notifId + "/read")
                .then().statusCode(200).body("data.unread", equalTo(0));
        givenAs(approver).when().get("/api/v1/notifications/inbox")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].readAt", notNullValue());

        // ─── Tout marquer lu, en une fois ───
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 100000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201);
        givenAs(approver).when().get("/api/v1/notifications/inbox/unread-count")
                .then().statusCode(200).body("data.unread", equalTo(1));
        givenAs(approver).contentType("application/json").body("{}")
                .when().post("/api/v1/notifications/inbox/read-all")
                .then().statusCode(200).body("data.unread", equalTo(0));
    }
}
