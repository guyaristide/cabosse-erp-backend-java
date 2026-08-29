package com.ntech.cabosse.qualitygrade;

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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Les seuils de qualité appartiennent au tenant.
 *
 * <p>Ils étaient écrits en dur dans un écran, présentés comme la référence
 * d'une filière et d'un pays donnés, et contredits par deux seuils dormant
 * dans les préférences sans être ni lus ni exposés : 8,5 et 10 en base
 * contre 8 et 9 à l'écran. Personne ne pouvait le voir, et aucune des deux
 * valeurs ne faisait foi puisque aucun calcul ne les lisait.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class QualityNormReferentialTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-nrm-" + TestFixtures.randomSlugSuffix(), "Coopérative Seuils");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
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

    private io.restassured.response.ValidatableResponse create(
            UserEntity admin, String code, String label, String acceptance, String refaction) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "elementCode": "%s", "label": "%s", "acceptanceMaxPct": %s%s }
                        """.formatted(code, label, acceptance,
                        refaction == null ? "" : ", \"refactionMaxPct\": " + refaction))
                .when().post("/api/v1/quality-norms").then();
    }

    @Test
    void a_structure_sets_its_own_thresholds() {
        UserEntity a = admin();

        // Une unité de séchage d'anacarde n'a pas les seuils du cacao.
        create(a, "moisture", "Humidité", "5", "6").statusCode(201);
        create(a, "outturn", "Rendement au décorticage", "48", null).statusCode(201);

        givenAs(a).when().get("/api/v1/quality-norms")
                .then().statusCode(200)
                .body("data", hasSize(2))
                .body("data[0].elementCode", equalTo("moisture"))
                .body("data[0].refactionMaxPct", equalTo(6));
    }

    @Test
    void a_deduction_threshold_below_the_acceptance_one_is_refused() {
        UserEntity a = admin();

        // « Accepté jusqu'à 9 %, réfaction jusqu'à 8 % » décrit une
        // fourchette qui commence après sa fin : ça ne se lit pas.
        create(a, "moisture", "Humidité", "9", "8").statusCode(422);
        create(a, "moisture", "Humidité", "8", "9").statusCode(201);
    }

    @Test
    void an_element_carries_a_single_threshold() {
        UserEntity a = admin();
        create(a, "moisture", "Humidité", "8", "9").statusCode(201);

        // Deux seuils sur le même élément ne se départagent pas.
        create(a, "MOISTURE", "Humidité bis", "7", null).statusCode(409);
    }

    @Test
    void a_threshold_without_a_range_is_accepted() {
        UserEntity a = admin();

        // Certains éléments n'ont pas de fourchette : au-delà du seuil
        // d'acceptation, c'est un refus, pas une décote.
        create(a, "moldy", "Fèves moisies", "6", null)
                .statusCode(201)
                .body("data.refactionMaxPct", equalTo(null));
    }
}
