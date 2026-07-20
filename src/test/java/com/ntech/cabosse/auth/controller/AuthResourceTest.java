package com.ntech.cabosse.auth.controller;

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
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.notNullValue;

/**
 * Cycle d'authentification HTTP : login, rotation du refresh token,
 * logout, et les refus (mauvais mot de passe sans indice, compte
 * inactif, token inconnu).
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AuthResourceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity activeUser(UserStatus status) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-auth-" + TestFixtures.randomSlugSuffix(), "Coopérative Auth");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "user@" + tenant.slug + ".ci";
        u.firstName = "Awa";
        u.lastName = "Traoré";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = status;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private static io.restassured.response.Response login(String email, String password) {
        return RestAssured.given()
                .contentType("application/json")
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .when().post("/api/v1/auth/login");
    }

    @Test
    void login_returns_access_and_refresh_tokens() {
        UserEntity u = activeUser(UserStatus.ACTIVE);
        login(u.email, TestFixtures.DEFAULT_PASSWORD)
                .then().statusCode(200)
                .body("data.accessToken", notNullValue())
                .body("data.refreshToken", notNullValue())
                .body("data.user.email", org.hamcrest.Matchers.equalTo(u.email));
    }

    @Test
    void bad_password_and_unknown_user_get_the_same_401() {
        UserEntity u = activeUser(UserStatus.ACTIVE);
        String msg1 = login(u.email, "mauvais-mot-de-passe")
                .then().statusCode(401).extract().path("statusMessage");
        String msg2 = login("inconnu@nulle-part.ci", "peu-importe")
                .then().statusCode(401).extract().path("statusMessage");
        // Même message générique : pas d'oracle d'existence de compte.
        org.junit.jupiter.api.Assertions.assertEquals(msg1, msg2);
    }

    @Test
    void disabled_user_cannot_login() {
        UserEntity u = activeUser(UserStatus.DISABLED);
        login(u.email, TestFixtures.DEFAULT_PASSWORD)
                .then().statusCode(401);
    }

    @Test
    void refresh_rotates_and_old_token_is_rejected() {
        UserEntity u = activeUser(UserStatus.ACTIVE);
        String refresh1 = login(u.email, TestFixtures.DEFAULT_PASSWORD)
                .then().statusCode(200).extract().path("data.refreshToken");

        String refresh2 = RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh1 + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(200)
                .body("data.accessToken", notNullValue())
                .extract().path("data.refreshToken");

        // Rejouer l'ancien token = replay détecté → 401.
        RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh1 + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);

        // La famille est révoquée après replay : le nouveau token tombe aussi.
        RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh2 + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    void logout_revokes_the_refresh_token() {
        UserEntity u = activeUser(UserStatus.ACTIVE);
        String refresh = login(u.email, TestFixtures.DEFAULT_PASSWORD)
                .then().statusCode(200).extract().path("data.refreshToken");

        RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/logout")
                .then().statusCode(204);

        RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    void unknown_refresh_token_is_rejected() {
        RestAssured.given()
                .contentType("application/json")
                .body("{\"refreshToken\":\"jeton-invente\"}")
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }
}
