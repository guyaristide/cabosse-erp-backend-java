package com.ntech.cabosse.tenant;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import jakarta.inject.Inject;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Une personne invitée arrive avec ses droits.
 *
 * <p>L'invitation ne portait que le drapeau d'administration. Une personne
 * invitée comme simple utilisatrice arrivait donc <strong>sans aucun
 * droit</strong> : elle pouvait se connecter, et tous les écrans lui
 * étaient fermés. Il fallait la retrouver ensuite dans la liste pour lui
 * attribuer un profil, en second geste, sans que rien ne l'annonce — ce
 * qui vidait de son sens le fait de préparer des profils à l'ouverture de
 * la structure.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class InviteWithProfileTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        tenant = fixtures.createActiveTenant(
                "coop-inv-" + TestFixtures.randomSlugSuffix(), "Structure Invitation");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Structure";
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

    private String createProfile(String code) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "Profil %s", "permissions": ["MEMBER_READ"] }
                        """.formatted(code, code))
                .when().post("/api/v1/tenant-roles")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse invite(String role, String profilesJson) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "email": "invite-%s@exemple.ci", "firstName": "Ama", "lastName": "Koffi",
                          "role": "%s", "tenantRoleIds": %s }
                        """.formatted(TestFixtures.randomSlugSuffix(), role, profilesJson))
                .when().post("/api/v1/me/tenant/admin/users")
                .then();
    }

    @Test
    void the_invited_person_arrives_with_the_profile_chosen_for_them() {
        String profileId = createProfile("MAGASINIER");

        invite("USER", "[\"" + profileId + "\"]")
                .statusCode(201)
                .body("data.tenantRoleIds", hasSize(1))
                .body("data.tenantRoleIds[0]", is(profileId));
    }

    @Test
    void an_administrator_takes_no_profile_since_they_already_hold_everything() {
        String profileId = createProfile("COMPTABLE_BIS");

        // Lui en poser un laisserait croire que le retirer restreint
        // quelque chose : c'est la même règle qu'à l'attribution.
        invite("TENANT_ADMIN", "[\"" + profileId + "\"]")
                .statusCode(422)
                .body("statusMessage", containsString("tous les droits"));
    }

    @Test
    void a_profile_that_does_not_exist_is_refused_rather_than_ignored() {
        invite("USER", "[\"" + UUID.randomUUID() + "\"]").statusCode(404);
    }

    @Test
    void the_invitation_still_works_without_a_profile() {
        // Le back-office invite d'abord un administrateur de structure :
        // exiger un profil au niveau de l'API fermerait cette porte.
        invite("USER", "[]").statusCode(201).body("data.tenantRoleIds", hasSize(0));
    }
}
