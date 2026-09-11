package com.ntech.cabosse.me;

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Sites de travail d'un utilisateur (demande du 11/09/2026).
 *
 * <p>Un magasinier qui tient un seul magasin ne devrait pas avoir à le
 * chercher dans une liste, ni risquer d'importer son carnet sur le
 * magasin du voisin. La liste borne son sélecteur ; vide, elle vaut tous
 * les sites, ce qui reste le réglage de départ.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class UserSitesTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Sites";
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
                "coop-sites-" + TestFixtures.randomSlugSuffix(), "Coopérative Sites");
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    @Test
    void the_admin_assigns_work_sites_and_they_travel_with_the_profile() {
        UserEntity admin = admin();
        UserEntity keeper = user("magasinier", Roles.USER);

        // Le quota de sites du plan limite la création : on prend le site
        // déjà ouvert par la structure quand il y en a un.
        String existing = givenAs(admin).when().get("/api/v1/sites")
                .then().statusCode(200).extract().path("data[0].id");
        String meagui = existing != null ? existing
                : givenAs(admin).contentType("application/json")
                        .body("{\"name\":\"Magasin Méagui\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"MAG-"
                                + TestFixtures.randomSlugSuffix() + "\"}")
                        .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");

        // Au départ, aucun site attribué : tous, et le sélecteur montre tout.
        givenAs(keeper).when().get("/api/v1/me")
                .then().statusCode(200).body("data.allowedSiteIds", hasSize(0));

        givenAs(admin).contentType("application/json")
                .body("{ \"siteIds\": [\"%s\"] }".formatted(meagui))
                .when().put("/api/v1/me/tenant/admin/users/" + keeper.id + "/sites")
                .then().statusCode(200)
                .body("data.allowedSiteIds", hasSize(1));

        givenAs(keeper).when().get("/api/v1/me")
                .then().statusCode(200)
                .body("data.allowedSiteIds", hasSize(1))
                .body("data.allowedSiteIds[0]", equalTo(meagui));

        // Un site étranger à la structure est refusé : une liste qui
        // garderait un identifiant mort masquerait un magasin sans raison.
        givenAs(admin).contentType("application/json")
                .body("{ \"siteIds\": [\"%s\"] }".formatted(UUID.randomUUID()))
                .when().put("/api/v1/me/tenant/admin/users/" + keeper.id + "/sites")
                .then().statusCode(422);

        // Le magasinier ne s'attribue pas ses propres sites : c'est la
        // gestion des utilisateurs, un droit d'administration.
        givenAs(keeper).contentType("application/json")
                .body("{ \"siteIds\": [] }")
                .when().put("/api/v1/me/tenant/admin/users/" + keeper.id + "/sites")
                .then().statusCode(403);

        // Revenir à tous les sites : la liste vide est le réglage neutre.
        givenAs(admin).contentType("application/json")
                .body("{ \"siteIds\": [] }")
                .when().put("/api/v1/me/tenant/admin/users/" + keeper.id + "/sites")
                .then().statusCode(200)
                .body("data.allowedSiteIds", hasSize(0));
    }
}
