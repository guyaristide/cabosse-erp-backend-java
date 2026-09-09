package com.ntech.cabosse.tenant;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Renvoi d'une invitation en attente (demandé le 09/09/2026) : un lien
 * expiré ou un mail jamais reçu ne condamnent pas le compte. Nouveau
 * jeton, nouvelle échéance, adresse corrigeable tant que personne ne
 * s'est jamais connecté ; un compte déjà actif est refusé.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ResendInvitationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        tenant = fixtures.createActiveTenant(
                "coop-resend-" + TestFixtures.randomSlugSuffix(), "Structure Renvoi");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Renvoi";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        admin = u;
    }

    @Test
    void a_pending_invitation_can_be_resent_and_its_address_fixed() {
        String suffix = TestFixtures.randomSlugSuffix();
        String wrong = "directeur-typo-" + suffix + "@yopmail.com";
        String right = "directeur-" + suffix + "@yopmail.com";
        String userId = givenAs(admin).contentType("application/json")
                .body("""
                        { "email": "%s", "firstName": "Directeur", "lastName": "Coop",
                          "role": "TENANT_ADMIN" }
                        """.formatted(wrong))
                .when().post("/api/v1/me/tenant/admin/users")
                .then().statusCode(201).extract().path("data.id");

        UserEntity before = users.findById(java.util.UUID.fromString(userId));
        String oldHash = before.invitationTokenHash;

        // ─── Renvoi avec adresse corrigée : nouveau jeton, l'ancien meurt ───
        givenAs(admin).contentType("application/json")
                .body("{ \"email\": \"%s\" }".formatted(right))
                .when().post("/api/v1/me/tenant/admin/users/" + userId + "/resend-invitation")
                .then().statusCode(200)
                .body("data.email", equalTo(right))
                .body("data.status", equalTo("INVITED"));
        UserEntity after = users.findById(java.util.UUID.fromString(userId));
        assertThat(after.invitationTokenHash).isNotEqualTo(oldHash);
        assertThat(after.invitationExpiresAt).isAfter(Instant.now());

        // ─── Renvoi sans corps : même adresse, jeton encore renouvelé ───
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/me/tenant/admin/users/" + userId + "/resend-invitation")
                .then().statusCode(200)
                .body("data.email", equalTo(right));

        // ─── L'adresse d'un autre compte est refusée ───
        givenAs(admin).contentType("application/json")
                .body("{ \"email\": \"%s\" }".formatted(admin.email))
                .when().post("/api/v1/me/tenant/admin/users/" + userId + "/resend-invitation")
                .then().statusCode(409);

        // ─── Un compte déjà actif ne se renvoie plus ───
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/me/tenant/admin/users/" + admin.id + "/resend-invitation")
                .then().statusCode(422);
    }
}
