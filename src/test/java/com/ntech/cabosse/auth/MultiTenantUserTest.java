package com.ntech.cabosse.auth;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.service.MembershipService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Un compte, plusieurs structures (SAAS-29).
 *
 * <p>Une même équipe travaille pour deux entreprises d'un même groupe.
 * Chacun a un compte, et une appartenance par entreprise : administrateur
 * ici, simple utilisateur là. Une fois connecté, il passe de l'une à
 * l'autre sans se reconnecter, et ce qu'une structure lui ferme ne touche
 * pas les autres.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MultiTenantUserTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject MembershipService memberships;

    private TenantEntity cocoa;
    private TenantEntity rubber;
    private UserEntity awa;

    @BeforeEach
    void setUp() {
        cocoa = fixtures.createActiveTenant("coop-cacao-" + TestFixtures.randomSlugSuffix(), "Coopérative Cacao");
        rubber = fixtures.createActiveTenant("coop-hevea-" + TestFixtures.randomSlugSuffix(), "Coopérative Hévéa");
        awa = account("awa", cocoa, Roles.TENANT_ADMIN);
        memberships.open(awa, rubber, Roles.USER, List.of(), "test");
    }

    private UserEntity account(String prefix, TenantEntity home, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "@" + home.slug + ".ci";
        u.firstName = "Awa";
        u.lastName = "Traoré";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = home.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private io.restassured.response.Response login(UserEntity u) {
        return RestAssured.given().contentType("application/json")
                .body("{\"email\":\"" + u.email + "\",\"password\":\"" + TestFixtures.DEFAULT_PASSWORD + "\"}")
                .when().post("/api/v1/auth/login");
    }

    private io.restassured.response.Response switchTo(String access, String refresh, TenantEntity target) {
        return RestAssured.given().contentType("application/json")
                .header("Authorization", "Bearer " + access)
                .body("{\"tenantId\":\"" + target.id + "\",\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/switch-tenant");
    }

    @Test
    void login_opens_the_home_structure_and_lists_every_structure_of_the_account() {
        login(awa).then().statusCode(200)
                .body("data.user.tenantId", equalTo(cocoa.id.toString()))
                .body("data.user.roles", hasItem(Roles.TENANT_ADMIN))
                .body("data.user.tenants", hasSize(2))
                .body("data.user.tenants.id", hasItem(rubber.id.toString()));
    }

    @Test
    void switching_structure_issues_a_session_with_the_rights_of_that_structure() {
        var first = login(awa).then().statusCode(200).extract();
        String access = first.path("data.accessToken");
        String refresh = first.path("data.refreshToken");

        var switched = switchTo(access, refresh, rubber).then().statusCode(200)
                .body("data.user.tenantId", equalTo(rubber.id.toString()))
                .body("data.user.roles", not(hasItem(Roles.TENANT_ADMIN)))
                .body("data.accessToken", notNullValue())
                .extract();

        // Le profil courant suit le jeton : structure, rôle et droits de là-bas.
        RestAssured.given().header("Authorization", "Bearer " + switched.path("data.accessToken"))
                .when().get("/api/v1/me").then().statusCode(200)
                .body("data.tenantId", equalTo(rubber.id.toString()))
                .body("data.tenantName", equalTo(rubber.name))
                .body("data.permissions", hasSize(0))
                .body("data.tenants", hasSize(2));

        // La session quittée est fermée : son jeton de rafraîchissement ne vaut plus rien.
        RestAssured.given().contentType("application/json")
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/api/v1/auth/refresh").then().statusCode(401);

        // Et la connexion suivante revient là où l'on était.
        login(awa).then().statusCode(200)
                .body("data.user.tenantId", equalTo(rubber.id.toString()));
    }

    @Test
    void a_structure_the_account_does_not_belong_to_does_not_exist_for_it() {
        TenantEntity other = fixtures.createActiveTenant("coop-autre-" + TestFixtures.randomSlugSuffix(), "Autre");
        var first = login(awa).then().statusCode(200).extract();
        switchTo(first.path("data.accessToken"), first.path("data.refreshToken"), other)
                .then().statusCode(404);
    }

    @Test
    void closing_the_door_of_one_structure_leaves_the_others_open() {
        UserEntity rubberAdmin = account("chef", rubber, Roles.TENANT_ADMIN);
        var session = login(awa).then().statusCode(200).extract();
        var inRubber = switchTo(session.path("data.accessToken"), session.path("data.refreshToken"), rubber)
                .then().statusCode(200).extract();

        givenAs(rubberAdmin).when().patch("/api/v1/me/tenant/admin/users/" + awa.id + "/active?value=false")
                .then().statusCode(200).body("data.status", equalTo("DISABLED"));

        // Sa session hévéa ne se rafraîchit plus, et la structure a disparu de sa liste.
        RestAssured.given().contentType("application/json")
                .body("{\"refreshToken\":\"" + inRubber.path("data.refreshToken") + "\"}")
                .when().post("/api/v1/auth/refresh").then().statusCode(401);
        login(awa).then().statusCode(200)
                .body("data.user.tenantId", equalTo(cocoa.id.toString()))
                .body("data.user.tenants", hasSize(1));

        // Vu du cacao, rien n'a changé : elle y administre toujours.
        givenAs(awa).when().get("/api/v1/me").then().statusCode(200)
                .body("data.roles", hasItem(Roles.TENANT_ADMIN));
        givenAs(rubberAdmin).when().get("/api/v1/me/tenant/admin/users").then().statusCode(200)
                .body("data.find { it.email == '" + awa.email + "' }.status", equalTo("DISABLED"));
    }

    @Test
    void inviting_a_known_address_opens_the_door_instead_of_refusing_it() {
        TenantEntity soap = fixtures.createActiveTenant("savon-" + TestFixtures.randomSlugSuffix(), "Savonnerie");
        UserEntity soapAdmin = account("admin", soap, Roles.TENANT_ADMIN);

        givenAs(soapAdmin).contentType("application/json")
                .body("""
                        { "email": "%s", "firstName": "Peu", "lastName": "Importe", "role": "USER", "tenantRoleIds": [] }
                        """.formatted(awa.email))
                .when().post("/api/v1/me/tenant/admin/users")
                .then().statusCode(201)
                .body("data.id", equalTo(awa.id.toString()))
                .body("data.firstName", equalTo("Awa"))
                .body("data.status", equalTo("ACTIVE"));

        // Un accès ouvert, pas une invitation : le journal le dit ainsi, et
        // le compte n'a reçu aucun nouveau lien d'activation.
        var audit = mongoClient.getDatabase("cabosse_control").getCollection("global_audit");
        org.junit.jupiter.api.Assertions.assertEquals(1,
                audit.countDocuments(com.mongodb.client.model.Filters.eq("eventType", "USER_ADDED_TO_TENANT")));
        org.junit.jupiter.api.Assertions.assertNull(users.findById(awa.id).invitationTokenHash);

        // Déjà de la maison : refusé comme avant.
        givenAs(soapAdmin).contentType("application/json")
                .body("""
                        { "email": "%s", "firstName": "Awa", "lastName": "Traoré", "role": "USER", "tenantRoleIds": [] }
                        """.formatted(awa.email))
                .when().post("/api/v1/me/tenant/admin/users")
                .then().statusCode(409);

        login(awa).then().statusCode(200).body("data.user.tenants", hasSize(3));
    }

    @Test
    void an_account_from_before_the_update_still_enters_its_structure() {
        // Aucune appartenance écrite : le compte seul porte sa structure.
        UserEntity legacy = account("ancien", cocoa, Roles.USER);
        login(legacy).then().statusCode(200)
                .body("data.user.tenantId", equalTo(cocoa.id.toString()))
                .body("data.user.tenants", hasSize(1));
        org.junit.jupiter.api.Assertions.assertTrue(
                memberships.resolve(legacy, cocoa.id).isPresent());
    }
}
