package com.ntech.cabosse.search;

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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * La recherche globale respecte les droits (backlog SAAS-03).
 *
 * <p>La palette interrogeait sept entités sans aucun contrôle : un
 * utilisateur sans droit de lecture sur un module voyait quand même ses
 * données remonter. Une recherche n'est pas une porte dérobée autour des
 * profils.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class GlobalSearchPermissionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = role.toLowerCase() + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Test";
        u.lastName = role;
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
    void results_follow_the_reader_rights_not_the_data() {
        tenant = fixtures.createActiveTenant(
                "coop-search-" + TestFixtures.randomSlugSuffix(), "Coopérative Recherche");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity admin = user(Roles.TENANT_ADMIN);

        // Une donnée par module : un producteur et un article.
        givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Recherchable\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Recherchable cacao\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201);

        // L'administrateur voit tout.
        givenAs(admin).queryParam("q", "Recherchable")
                .when().get("/api/v1/search")
                .then().statusCode(200)
                .body("data.type", hasItem("member"))
                .body("data.type", hasItem("article"));

        // Un compte sans profil : la recherche ne montre rien, comme ses
        // écrans. Le droit décide, pas la donnée.
        UserEntity sansProfil = user(Roles.USER);
        givenAs(sansProfil).queryParam("q", "Recherchable")
                .when().get("/api/v1/search")
                .then().statusCode(200)
                .body("data.type", not(hasItem("member")))
                .body("data.type", not(hasItem("article")));

        // Avec un profil limité aux référentiels : l'article remonte, le
        // producteur reste invisible.
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Magasinier\", \"permissions\": [\"REFERENTIAL_READ\"] }")
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + sansProfil.id)
                .then().statusCode(204);

        givenAs(sansProfil).queryParam("q", "Recherchable")
                .when().get("/api/v1/search")
                .then().statusCode(200)
                .body("data.type", hasItem("article"))
                .body("data.type", not(hasItem("member")));
    }
}
