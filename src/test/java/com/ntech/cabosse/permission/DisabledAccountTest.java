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
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

/**
 * Désactiver un compte le désactive tout de suite.
 *
 * <p>Les droits sont relus à chaque appel pour qu'un retrait prenne
 * effet sans attendre ; la désactivation, elle, ne faisait que changer
 * un statut que personne ne relisait. Le compte gardait tous ses droits
 * jusqu'à l'expiration du jeton d'accès, et son jeton de
 * rafraîchissement lui rendait une session neuve pendant trente jours
 * tant qu'il n'était pas présenté. Ces tests tiennent les deux : le
 * jeton d'accès encore valide ne lit plus rien, et le rafraîchissement
 * est refusé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DisabledAccountTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-off-" + TestFixtures.randomSlugSuffix(), "Coopérative Sortie");
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
    void a_disabled_account_loses_its_rights_and_its_sessions_at_once() {
        UserEntity admin = tenantAdmin();
        UserEntity lecteur = withRights(admin, "lecteur", "MEMBER_READ");

        var session = RestAssured.given().contentType("application/json")
                .body("{\"email\":\"" + lecteur.email + "\",\"password\":\""
                        + TestFixtures.DEFAULT_PASSWORD + "\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200).extract();
        String access = session.path("data.accessToken");
        String refresh = session.path("data.refreshToken");

        RestAssured.given().header("Authorization", "Bearer " + access)
                .when().get("/api/v1/members").then().statusCode(200);

        givenAs(admin).when()
                .patch("/api/v1/me/tenant/admin/users/" + lecteur.id + "/active?value=false")
                .then().statusCode(200);

        // Le jeton d'accès a encore un quart d'heure devant lui : il ne
        // doit plus rien ouvrir pour autant.
        RestAssured.given().header("Authorization", "Bearer " + access)
                .when().get("/api/v1/members").then().statusCode(403);
        // Et la session ne se renouvelle pas.
        RestAssured.given().contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    void reactivating_gives_the_rights_back_but_not_the_old_sessions() {
        UserEntity admin = tenantAdmin();
        UserEntity lecteur = withRights(admin, "lecteur", "MEMBER_READ");
        String refresh = RestAssured.given().contentType("application/json")
                .body("{\"email\":\"" + lecteur.email + "\",\"password\":\""
                        + TestFixtures.DEFAULT_PASSWORD + "\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200).extract().path("data.refreshToken");

        givenAs(admin).when()
                .patch("/api/v1/me/tenant/admin/users/" + lecteur.id + "/active?value=false")
                .then().statusCode(200);
        givenAs(admin).when()
                .patch("/api/v1/me/tenant/admin/users/" + lecteur.id + "/active?value=true")
                .then().statusCode(200);

        givenAs(lecteur).when().get("/api/v1/members").then().statusCode(200);
        // La session coupée reste coupée : il faut se reconnecter.
        RestAssured.given().contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }
}
