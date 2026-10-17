package com.ntech.cabosse.permission;

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

/**
 * Lire de l'argent demande un droit, comme en écrire.
 *
 * <p>Les avances aux délégués, les crédits aux producteurs, les
 * règlements et la trésorerie exigeaient un droit pour chaque geste,
 * et rien pour lire : un compte sans aucun profil consultait le solde
 * de caisse, les états de délégué et les échéanciers de crédit. Ces
 * tests tiennent qu'un compte sans profil ne lit rien de tout cela, que
 * le droit de lecture du domaine suffit, et qu'il ne déborde pas sur le
 * domaine voisin.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class FinancialReadRightTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-read-" + TestFixtures.randomSlugSuffix(), "Coopérative Lecture");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user(Roles.TENANT_ADMIN, "admin");
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Test";
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

    private UserEntity withRights(UserEntity admin, String prefix, String... permissions) {
        UserEntity u = user(Roles.USER, prefix);
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }"
                        .formatted(prefix + "-" + TestFixtures.randomSlugSuffix(), perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id)
                .then().statusCode(204);
        return u;
    }

    @Test
    void a_user_without_any_profile_reads_no_money() {
        UserEntity admin = tenantAdmin();
        UserEntity nobody = user(Roles.USER, "nobody");

        givenAs(nobody).when().get("/api/v1/collector-advances").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/collector-advances/delegates/statement").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/member-credits").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/member-credits/producers/statement").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/producer-payments").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/treasury/transfers").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/treasury/cash-position").then().statusCode(403);
        givenAs(nobody).when().get("/api/v1/treasury/reconciliation").then().statusCode(403);

        // L'administrateur, lui, lit tout cela sans profil.
        givenAs(admin).when().get("/api/v1/collector-advances").then().statusCode(200);
        givenAs(admin).when().get("/api/v1/member-credits").then().statusCode(200);
        givenAs(admin).when().get("/api/v1/producer-payments").then().statusCode(200);
        givenAs(admin).when().get("/api/v1/treasury/transfers").then().statusCode(200);
    }

    @Test
    void the_read_right_of_a_domain_opens_it_and_nothing_else() {
        UserEntity admin = tenantAdmin();
        UserEntity collecte = withRights(admin, "collecte", "COLLECTION_READ");

        givenAs(collecte).when().get("/api/v1/collector-advances").then().statusCode(200);
        givenAs(collecte).when().get("/api/v1/producer-payments").then().statusCode(200);
        // La collecte ne donne ni la trésorerie ni les crédits.
        givenAs(collecte).when().get("/api/v1/treasury/transfers").then().statusCode(403);
        givenAs(collecte).when().get("/api/v1/member-credits").then().statusCode(403);
    }

    @Test
    void a_proof_on_a_credit_file_is_not_removed_without_the_right() {
        UserEntity admin = tenantAdmin();
        UserEntity nobody = user(Roles.USER, "nobody");
        java.util.UUID any = java.util.UUID.randomUUID();

        // Le refus vient du droit, avant même de chercher le dossier.
        givenAs(nobody).when()
                .delete("/api/v1/member-credits/" + any + "/attachments/" + any)
                .then().statusCode(403);
        givenAs(nobody).when()
                .delete("/api/v1/collector-advances/" + any + "/attachments/" + any)
                .then().statusCode(403);
        givenAs(admin).when()
                .delete("/api/v1/member-credits/" + any + "/attachments/" + any)
                .then().statusCode(404);
    }
}
