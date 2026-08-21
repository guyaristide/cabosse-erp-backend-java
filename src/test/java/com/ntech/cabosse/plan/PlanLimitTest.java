package com.ntech.cabosse.plan;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
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

import static org.hamcrest.Matchers.containsString;

/**
 * Plafonds du plan tarifaire (backlog SAAS-02).
 *
 * <p>Le modèle économique vend des paliers : tant de comptes, tant de
 * producteurs. Le contrat du premier client promet 500 producteurs et
 * 20 comptes ; jusqu'ici, rien dans la plateforme ne savait tenir cette
 * promesse ni la mesurer.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PlanLimitTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject PlanRepository plans;

    private TenantEntity tenant;

    private UserEntity adminOnPlan(int maxUsers, int maxMembers) {
        String planCode = "cap-" + TestFixtures.randomSlugSuffix();
        PlanEntity plan = new PlanEntity();
        plan.code = planCode;
        plan.name = "Palier de test";
        plan.maxUsers = maxUsers;
        plan.maxSites = 99;
        plan.maxMembers = maxMembers;
        plan.active = true;
        plans.persist(plan);

        tenant = fixtures.createActiveTenant(
                "coop-cap-" + TestFixtures.randomSlugSuffix(), "Coopérative Plafonds");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.planCode = planCode;
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tenant";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private io.restassured.response.ValidatableResponse invite(UserEntity admin, String prefix) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "email": "%s-%s@%s.ci", "firstName": "Test", "lastName": "Invite",
                          "role": "USER" }
                        """.formatted(prefix, TestFixtures.randomSlugSuffix(), tenant.slug))
                .when().post("/api/v1/me/tenant/admin/users")
                .then();
    }

    private io.restassured.response.ValidatableResponse createMember(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + name + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members")
                .then();
    }

    @Test
    void a_seat_frees_up_when_an_account_is_disabled() {
        UserEntity admin = adminOnPlan(2, 0);

        // L'administrateur occupe le premier siège, l'invitation le second.
        String secondId = invite(admin, "u2").statusCode(201).extract().path("data.id");

        // Troisième compte : le plafond parle, et il se nomme.
        invite(admin, "u3").statusCode(422)
                .body("statusMessage", containsString("Plafond du plan"));

        // Désactiver un compte libère sa place : c'est toute la différence
        // entre un plafond et une punition.
        givenAs(admin).when()
                .patch("/api/v1/me/tenant/admin/users/" + secondId + "/active?value=false")
                .then().statusCode(200);
        invite(admin, "u3bis").statusCode(201);
    }

    @Test
    void members_stop_at_the_plan_ceiling() {
        UserEntity admin = adminOnPlan(0, 3);

        createMember(admin, "Kouassi").statusCode(201);
        createMember(admin, "Yao").statusCode(201);
        createMember(admin, "Bamba").statusCode(201);

        createMember(admin, "Traore").statusCode(422)
                .body("statusMessage", containsString("Plafond du plan"))
                .body("statusMessage", containsString("3 producteurs"));
    }

    @Test
    void an_import_is_refused_as_a_whole_not_cut_in_the_middle() {
        UserEntity admin = adminOnPlan(0, 3);
        createMember(admin, "Kouassi").statusCode(201);
        createMember(admin, "Yao").statusCode(201);

        // Deux lignes pour une seule place : rien ne s'écrit, plutôt qu'un
        // import arrêté au milieu dans un état que personne ne sait décrire.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "lastName": "Diallo", "gender": "MALE" },
                          { "rowNumber": 2, "lastName": "Sanogo", "gender": "MALE" } ]
                        """)
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(422)
                .body("statusMessage", containsString("Plafond du plan"));

        // Une seule ligne : la dernière place se prend normalement.
        givenAs(admin).contentType("application/json")
                .body("[ { \"rowNumber\": 1, \"lastName\": \"Diallo\", \"gender\": \"MALE\" } ]")
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200);
    }

    @Test
    void a_plan_without_ceiling_constrains_nothing() {
        UserEntity admin = adminOnPlan(0, 0);
        for (int i = 0; i < 4; i++) {
            createMember(admin, "Membre" + i).statusCode(201);
        }
    }
}
