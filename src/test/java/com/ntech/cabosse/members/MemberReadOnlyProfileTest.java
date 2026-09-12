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
import java.util.UUID;

/**
 * Le registre des producteurs se consulte largement et ne s'écrit que
 * par le droit dédié (signalé par l'expert-comptable le 12/09/2026 : un
 * magasinier pouvait ouvrir la modification d'un producteur).
 *
 * <p>Détacher une pièce d'identité est une écriture comme une autre : la
 * garde de classe seule laissait la consultation suffire.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberReadOnlyProfileTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Registre";
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
                "coop-reg-" + TestFixtures.randomSlugSuffix(), "Coopérative Registre");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    @Test
    void a_read_only_profile_consults_producers_without_writing_them() {
        UserEntity admin = admin();
        UserEntity keeper = user("magasinier", Roles.USER);

        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Magasinier\", \"permissions\": [\"MEMBER_READ\", \"STOCK_READ\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + keeper.id).then().statusCode(204);

        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"KOUAME\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        // Il consulte : c'est le sens de son droit.
        givenAs(keeper).when().get("/api/v1/members").then().statusCode(200);
        givenAs(keeper).when().get("/api/v1/members/" + memberId).then().statusCode(200);

        // Il n'ajoute pas, ne modifie pas, ne radie pas.
        givenAs(keeper).contentType("application/json")
                .body("{\"lastName\":\"TRAORE\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(403);
        givenAs(keeper).contentType("application/json")
                .body("{\"lastName\":\"KOUAME MODIFIE\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().put("/api/v1/members/" + memberId).then().statusCode(403);
        givenAs(keeper).contentType("application/json")
                .body("{ \"reason\": \"essai\" }")
                .when().post("/api/v1/members/" + memberId + "/retire").then().statusCode(403);

        // Et il ne détache pas une pièce d'identité du dossier.
        givenAs(keeper).when()
                .delete("/api/v1/members/" + memberId + "/documents/" + UUID.randomUUID())
                .then().statusCode(403);

        // L'import non plus : il crée et modifie des producteurs en masse.
        givenAs(keeper).contentType("application/json").body("[]")
                .when().post("/api/v1/members/import/preview").then().statusCode(403);
    }
}
