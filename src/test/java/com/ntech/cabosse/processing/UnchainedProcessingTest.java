package com.ntech.cabosse.processing;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Chaque étape de transformation se tient debout seule.
 *
 * <p>Les quatre étapes étaient enchaînées par des champs obligatoires :
 * un contrôle qualité exigeait un lot de séchage, qui exigeait des bacs de
 * fermentation, qui exigeaient des récoltes, qui exigeaient des parcelles.
 * Six filières sur sept n'activent que le séchage (anacarde, manioc, maïs,
 * riz, fruits, café) : elles voyaient le module, l'ouvraient, et ne
 * pouvaient <strong>rien</strong> y créer.</p>
 *
 * <p>Ce qui compte ici : la chaîne complète du cacao continue de
 * fonctionner, et les maillons se prennent aussi séparément. Ce n'est pas
 * un assouplissement de la règle, c'est la fin d'une règle qui n'avait
 * jamais été voulue.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class UnchainedProcessingTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    /** @param activities filières du tenant, qui décident de ses capacités */
    private UserEntity adminOf(String... activities) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-chn-" + TestFixtures.randomSlugSuffix(), "Structure Transformation");
        tenant.organizationModel = TenantOrganizationModel.PRIVATE_COMPANY;
        tenant.activities = new ArrayList<>();
        for (String code : activities) {
            TenantActivity activity = new TenantActivity();
            activity.code = code;
            activity.label = code;
            activity.isPrimary = tenant.activities.isEmpty();
            tenant.activities.add(activity);
        }
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

    @Test
    void a_drying_only_industry_can_open_a_drying_batch() {
        // Une unité de séchage de manioc : ni parcelles, ni fermentation.
        UserEntity a = adminOf("manioc-attieke");

        givenAs(a).contentType("application/json")
                .body("""
                        { "method": "SOLAR", "weightInKg": 1200 }
                        """)
                .when().post("/api/v1/drying-batches")
                .then().statusCode(201)
                .body("data.fermentationBatchIds", hasSize(0))
                .body("data.status", equalTo("DRYING"));
    }

    @Test
    void a_quality_check_stands_without_a_drying_batch() {
        UserEntity a = adminOf("manioc-attieke");

        givenAs(a).contentType("application/json")
                .body("""
                        { "conformOverall": true, "humidityPct": 12.5, "acceptedKg": 900 }
                        """)
                .when().post("/api/v1/bean-quality-checks")
                .then().statusCode(201)
                .body("data.dryingBatchId", equalTo(null));

        // Et deux contrôles autonomes coexistent : l'absence de lot n'est
        // pas une valeur en double.
        givenAs(a).contentType("application/json")
                .body("""
                        { "conformOverall": true, "humidityPct": 11.0, "acceptedKg": 400 }
                        """)
                .when().post("/api/v1/bean-quality-checks")
                .then().statusCode(201);

        givenAs(a).when().get("/api/v1/bean-quality-checks?perPage=50")
                .then().statusCode(200)
                .body("data.items", hasSize(2));
    }

    @Test
    void a_drying_batch_named_by_an_unknown_ferment_is_still_refused() {
        UserEntity a = adminOf("cacao-production");

        // Facultatif ne veut pas dire ignoré : un identifiant donné mais
        // inconnu est une erreur de saisie, pas une absence.
        givenAs(a).contentType("application/json")
                .body("""
                        { "method": "SOLAR", "fermentationBatchIds": ["%s"] }
                        """.formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/drying-batches")
                .then().statusCode(422);
    }

    @Test
    void a_quality_check_naming_an_unknown_batch_is_still_refused() {
        UserEntity a = adminOf("cacao-production");

        givenAs(a).contentType("application/json")
                .body("""
                        { "dryingBatchId": "%s", "conformOverall": true }
                        """.formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/bean-quality-checks")
                .then().statusCode(404);
    }

    @Test
    void the_cocoa_chain_still_links_its_steps() {
        UserEntity a = adminOf("cacao-production");

        String drying = givenAs(a).contentType("application/json")
                .body("{ \"method\": \"SOLAR\", \"weightInKg\": 500 }")
                .when().post("/api/v1/drying-batches").then().statusCode(201)
                .extract().path("data.id");

        // Le lot est repris sur le contrôle, avec sa référence.
        String ref = givenAs(a).when().get("/api/v1/drying-batches/" + drying)
                .then().statusCode(200).extract().path("data.ref");

        givenAs(a).contentType("application/json")
                .body("""
                        { "dryingBatchId": "%s", "conformOverall": true, "acceptedKg": 480 }
                        """.formatted(drying))
                .when().post("/api/v1/bean-quality-checks")
                .then().statusCode(201)
                .body("data.dryingBatchId", equalTo(drying))
                .body("data.dryingBatchRef", equalTo(ref));

        // Un lot ne porte qu'un seul contrôle : deux verdicts sur la même
        // matière ne se départagent pas.
        givenAs(a).contentType("application/json")
                .body("""
                        { "dryingBatchId": "%s", "conformOverall": false }
                        """.formatted(drying))
                .when().post("/api/v1/bean-quality-checks")
                .then().statusCode(422);
    }

    @Test
    void a_fermentation_batch_opens_without_a_harvest() {
        // Une structure qui achète sa matière au lieu de la récolter.
        UserEntity a = adminOf("cacao-production");

        givenAs(a).contentType("application/json")
                .body("{ \"weightInKg\": 800 }")
                .when().post("/api/v1/fermentation-batches")
                .then().statusCode(201)
                .body("data.harvestIds", hasSize(0));
    }
}
