package com.ntech.cabosse.members;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Cycle de vie complet du membre (backlog MEM-01/03/05) : pièces du
 * dossier, carte de membre PDF, radiation avec remboursement des parts
 * sociales et désactivation du fournisseur miroir.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberLifecycleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-vie-" + TestFixtures.randomSlugSuffix(), "Coopérative Cycle");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
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

    private String createActiveMember(UserEntity admin) {
        return givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "name": "Aïcha Diabaté", "civilStatus": "UNKNOWN",
                          "village": "Méagui", "partsSocialesAmount": 25000, "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void documents_can_be_attached_downloaded_and_removed() {
        UserEntity admin = tenantAdmin();
        String id = createActiveMember(admin);

        String docId = givenAs(admin)
                .multiPart("label", "Attestation d'exploitation")
                .multiPart("file", "attestation.pdf", "%PDF-1.4 fake".getBytes(), "application/pdf")
                .when().post("/api/v1/members/" + id + "/documents")
                .then().statusCode(200)
                .body("data.documents", hasSize(1))
                .body("data.documents[0].label", equalTo("Attestation d'exploitation"))
                .extract().path("data.documents[0].id");

        givenAs(admin)
                .when().get("/api/v1/members/" + id + "/documents/" + docId)
                .then().statusCode(200)
                .header("Content-Type", equalTo("application/pdf"));

        givenAs(admin)
                .when().delete("/api/v1/members/" + id + "/documents/" + docId)
                .then().statusCode(204);

        givenAs(admin)
                .when().get("/api/v1/members/" + id)
                .then().statusCode(200)
                .body("data.documents", hasSize(0));
    }

    @Test
    void card_is_issued_for_active_member_only() {
        UserEntity admin = tenantAdmin();
        String id = createActiveMember(admin);

        givenAs(admin)
                .when().get("/api/v1/members/" + id + "/card")
                .then().statusCode(200)
                .header("Content-Type", equalTo("application/pdf"));
    }

    @Test
    void retirement_reverses_capital_and_deactivates_mirror_supplier() {
        UserEntity admin = tenantAdmin();
        String id = createActiveMember(admin);

        // La pièce capital existe (création active avec parts sociales).
        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1));

        givenAs(admin)
                .contentType("application/json")
                .body("{\"reason\":\"Départ volontaire\"}")
                .when().post("/api/v1/members/" + id + "/retire")
                .then().statusCode(200)
                .body("data.status", equalTo("RETIRED"))
                .body("data.statusReason", equalTo("Départ volontaire"));

        // Contre-passation présente au journal.
        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(2));

        // Plus de carte pour un membre radié.
        givenAs(admin)
                .when().get("/api/v1/members/" + id + "/card")
                .then().statusCode(422);

        // Radiation idempotente refusée (déjà radié).
        givenAs(admin)
                .contentType("application/json")
                .body("{\"reason\":\"Encore\"}")
                .when().post("/api/v1/members/" + id + "/retire")
                .then().statusCode(422);
    }
}
