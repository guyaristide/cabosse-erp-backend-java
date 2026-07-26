package com.ntech.cabosse.shared.security;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.tenant.entity.TenantEntity;
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
 * Application des rôles sur les endpoints sensibles : un utilisateur
 * simple ne touche pas aux actions d'administration tenant, un admin
 * tenant ne touche pas au back-office plateforme, et les endpoints
 * authentifiés refusent les requêtes anonymes.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class RoleEnforcementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private record Pair(UserEntity admin, UserEntity user) {}

    private Pair tenantPair() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-roles-" + TestFixtures.randomSlugSuffix(), "Coopérative Rôles");
        UserEntity admin = newUser(tenant, Roles.TENANT_ADMIN, "admin");
        UserEntity user = newUser(tenant, Roles.USER, "agent");
        return new Pair(admin, user);
    }

    private UserEntity newUser(TenantEntity tenant, String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "@" + tenant.slug + ".ci";
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

    @Test
    void simple_user_cannot_use_tenant_admin_actions() {
        Pair p = tenantPair();

        // Verrouillage de période : réservé TENANT_ADMIN.
        givenAs(p.user())
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/2026-01/lock")
                .then().statusCode(403);

        // Arrêté d'exercice : réservé TENANT_ADMIN.
        givenAs(p.user())
                .contentType("application/json")
                .body("{}")
                .when().post("/api/v1/accounting/fiscal-years/close")
                .then().statusCode(403);

        // Validation d'OD : réservée TENANT_ADMIN.
        String odId = givenAs(p.admin())
                .contentType("application/json")
                .body("""
                        { "date": "2026-06-15", "libelle": "OD test",
                          "lines": [
                            { "account": "601", "libelle": "d", "debitFcfa": 1000 },
                            { "account": "401", "libelle": "c", "creditFcfa": 1000 }
                          ] }
                        """)
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .extract().path("data.id");
        givenAs(p.user())
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(403);
    }

    @Test
    void tenant_admin_cannot_reach_platform_backoffice() {
        Pair p = tenantPair();

        givenAs(p.admin())
                .when().get("/api/v1/admin/tenants?page=0&perPage=20")
                .then().statusCode(403);

        givenAs(p.admin())
                .when().get("/api/v1/admin/audit")
                .then().statusCode(403);
    }

    @Test
    void anonymous_requests_are_rejected_with_401() {
        RestAssured.given()
                .when().get("/api/v1/articles")
                .then().statusCode(401);

        RestAssured.given()
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(401);
    }
}
