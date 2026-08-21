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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Droits et profils du tenant.
 *
 * <p>Le rôle {@code USER} ne disait rien de ce qu'une personne avait le
 * droit de faire : un magasinier et un comptable portaient les mêmes
 * accès. L'administrateur compose désormais des profils, et le catalogue
 * qu'on lui présente suit les capacités réellement activées.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantPermissionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin(TenantOrganizationModel model) {
        tenant = fixtures.createActiveTenant(
                "coop-perm-" + TestFixtures.randomSlugSuffix(), "Coopérative Droits");
        tenant.organizationModel = model;
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
                .when().put("/api/v1/tenant-roles/users/" + target.id)
                .then().statusCode(204);
    }

    @Test
    void the_catalog_follows_the_capabilities_of_the_tenant() {
        UserEntity a = admin(TenantOrganizationModel.COOPERATIVE);

        // Une coopérative a des membres et collecte : ces droits lui sont
        // proposés.
        givenAs(a).when().get("/api/v1/tenant-roles/permissions")
                .then().statusCode(200)
                .body("data.code", hasItem("MEMBER_CREDIT_APPROVE"))
                .body("data.code", hasItem("COLLECTION_RECEIPT_WRITE"))
                // Sans filière déclarée, ni séchage ni conformité forestière :
                // ces droits n'apparaissent pas, plutôt que d'offrir des
                // cases sans effet.
                .body("data.code", not(hasItem("DRYING_WRITE")))
                .body("data.code", not(hasItem("EUDR_WRITE")));
    }

    @Test
    void a_company_without_members_is_not_offered_producer_rights() {
        UserEntity a = admin(TenantOrganizationModel.PRIVATE_COMPANY);

        givenAs(a).when().get("/api/v1/tenant-roles/permissions")
                .then().statusCode(200)
                .body("data.code", not(hasItem("MEMBER_CREDIT_APPROVE")))
                .body("data.code", hasItem("STOCK_MOVE"));
    }

    @Test
    void a_profile_grants_exactly_what_it_lists() {
        UserEntity a = admin(TenantOrganizationModel.COOPERATIVE);
        UserEntity magasinier = user(Roles.USER, "magasinier");

        String roleId = createRole(a, "Magasinier", "STOCK_READ", "STOCK_MOVE", "MEMBER_READ");
        assign(a, magasinier, roleId);

        givenAs(magasinier).when().get("/api/v1/me")
                .then().statusCode(200)
                .body("data.permissions", hasItem("STOCK_MOVE"))
                .body("data.permissions", not(hasItem("MEMBER_CREDIT_APPROVE")));

        // Le droit refusé nomme ce qui manque, au lieu d'un refus muet.
        givenAs(magasinier).contentType("application/json")
                .body("{ \"memberId\": \"%s\", \"kind\": \"CREDIT\", \"amountFcfa\": 50000 }"
                        .formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/member-credits")
                .then().statusCode(403)
                .body("statusMessage", containsString("Droit requis"));
    }

    @Test
    void the_tenant_admin_holds_everything_without_a_profile() {
        UserEntity a = admin(TenantOrganizationModel.COOPERATIVE);

        givenAs(a).when().get("/api/v1/me")
                .then().statusCode(200)
                .body("data.permissions", hasItem("MEMBER_CREDIT_APPROVE"))
                .body("data.permissions", hasItem("USER_MANAGE"))
                // Mais rien qui dépasse ses capacités : le séchage n'est pas
                // activé, il n'a donc pas ce droit non plus.
                .body("data.permissions", not(hasItem("DRYING_WRITE")));

        // Lui attribuer un profil n'aurait aucun sens : c'est refusé plutôt
        // que d'être accepté sans effet.
        String roleId = createRole(a, "Comptable", "ACCOUNTING_READ");
        givenAs(a).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + a.id)
                .then().statusCode(422)
                .body("statusMessage", containsString("tous les droits"));
    }

    @Test
    void a_user_without_a_profile_can_do_nothing() {
        admin(TenantOrganizationModel.COOPERATIVE);
        UserEntity orphan = user(Roles.USER, "sansprofil");

        givenAs(orphan).when().get("/api/v1/me")
                .then().statusCode(200)
                .body("data.permissions", equalTo(java.util.List.of()));
    }

    @Test
    void a_profile_still_held_cannot_be_deleted() {
        UserEntity a = admin(TenantOrganizationModel.COOPERATIVE);
        UserEntity porteur = user(Roles.USER, "porteur");
        String roleId = createRole(a, "Comptable", "ACCOUNTING_READ");
        assign(a, porteur, roleId);

        givenAs(a).when().delete("/api/v1/tenant-roles/" + roleId)
                .then().statusCode(422)
                .body("statusMessage", containsString("attribué"));
    }

    @Test
    void a_profile_signals_the_rights_its_capabilities_make_idle() {
        UserEntity a = admin(TenantOrganizationModel.PRIVATE_COMPANY);

        // Un droit hors capacités est refusé à la composition : mieux vaut
        // le dire que de le stocker sans effet.
        givenAs(a).contentType("application/json")
                .body("{ \"name\": \"Sécheur\", \"permissions\": [\"DRYING_WRITE\"] }")
                .when().post("/api/v1/tenant-roles")
                .then().statusCode(201)
                .body("data.inactivePermissions", hasItem("DRYING_WRITE"));
    }

    @Test
    void an_unknown_right_is_refused() {
        UserEntity a = admin(TenantOrganizationModel.COOPERATIVE);
        givenAs(a).contentType("application/json")
                .body("{ \"name\": \"Bidon\", \"permissions\": [\"TOUT_POUVOIR\"] }")
                .when().post("/api/v1/tenant-roles")
                .then().statusCode(422)
                .body("statusMessage", containsString("Droit inconnu"));
    }
}
