package com.ntech.cabosse.customer;

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
import java.util.Arrays;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Qui peut nommer le client d'un chargement.
 *
 * <p>Un magasinier compose un bordereau de sortie et doit désigner où
 * part le camion. Son profil ne porte pas la lecture des référentiels,
 * et il n'a pas à la porter : ce serait lui ouvrir articles, campagnes
 * et plan comptable pour choisir un destinataire. Le sélecteur revenait
 * donc vide, sans rien dire, et l'écran laissait croire qu'aucun client
 * n'existait alors que le serveur refusait la lecture (relevé sur la
 * démo le 22/09/2026, magasinier de SCOOPANAB).</p>
 *
 * <p>Troisième fois que ce cas se pose, après le sélecteur de campagne
 * et celui de site. La différence ici : la liste porte des données
 * commerciales, donc elle ne s'ouvre pas à tout compte authentifié mais
 * aux droits d'exploitation qui en ont l'usage.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CustomerSelectorAccessTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-cli-" + TestFixtures.randomSlugSuffix(), "Coopérative Clients");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return userIn("admin", Roles.TENANT_ADMIN);
    }

    private UserEntity userIn(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Agent";
        u.lastName = "Magasin";
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

    /** Un compte du tenant porteur des seuls droits énumérés. */
    private UserEntity staff(UserEntity admin, String prefix, String roleName, String... perms) {
        UserEntity u = userIn(prefix, Roles.USER);
        String list = String.join(", ",
                Arrays.stream(perms).map(p -> "\"" + p + "\"").toList());
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(roleName, list))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id).then().statusCode(204);
        return u;
    }

    private void createCustomer(UserEntity who, String name) {
        givenAs(who).contentType("application/json")
                .body("{ \"name\": \"%s\", \"type\": \"COMPANY\" }".formatted(name))
                .when().post("/api/v1/customers").then().statusCode(201);
    }

    @Test
    void le_magasinier_lit_les_clients_pour_composer_un_chargement() {
        UserEntity admin = admin();
        createCustomer(admin, "ZAMACOM");
        UserEntity storekeeper = staff(admin, "magasinier", "Magasiniers",
                "STOCK_READ", "STOCK_MOVE");

        givenAs(storekeeper).queryParam("page", 0).queryParam("perPage", 100)
                .when().get("/api/v1/customers")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].name", equalTo("ZAMACOM"));
    }

    @Test
    void le_vendeur_aussi() {
        UserEntity admin = admin();
        createCustomer(admin, "GCB");
        UserEntity seller = staff(admin, "vendeur", "Vendeurs", "SALE_READ");

        givenAs(seller).when().get("/api/v1/customers")
                .then().statusCode(200).body("data.items", hasSize(1));
    }

    @Test
    void un_compte_sans_aucun_de_ces_droits_reste_refuse() {
        UserEntity admin = admin();
        createCustomer(admin, "GCB");
        // La liste porte des données commerciales : elle ne s'ouvre pas à
        // tout compte authentifié, contrairement au site et à la campagne.
        UserEntity outsider = staff(admin, "rh", "Paie", "MEMBER_READ");

        givenAs(outsider).when().get("/api/v1/customers").then().statusCode(403);
    }

    @Test
    void ecrire_reste_reserve_au_referentiel() {
        UserEntity admin = admin();
        UserEntity storekeeper = staff(admin, "magasinier", "Magasiniers", "STOCK_READ");

        // Lire pour choisir, oui ; créer un client, non.
        givenAs(storekeeper).contentType("application/json")
                .body("{ \"name\": \"Interdit\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(403);
    }
}
