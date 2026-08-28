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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * La grille qualité appartient au tenant, pas au code.
 *
 * <p>Le grade d'un lot était un enum figé au vocabulaire du cacao ivoirien.
 * Toute autre filière devait s'y plier : l'hévéa classe en RSS1 à RSS5,
 * l'anacarde raisonne en calibre. Et les ventes portaient déjà une seconde
 * nomenclature, saisie en texte libre et vérifiée par personne : deux
 * vocabulaires concurrents pour une seule notion.</p>
 *
 * <p>Une structure qui n'a rien classé démarre donc avec une grille
 * <strong>vide</strong> et nomme ses grades elle-même. C'est le prix, et
 * l'intérêt, d'un référentiel.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class QualityGradeReferentialTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-grd-" + TestFixtures.randomSlugSuffix(), "Coopérative Grades");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Le contrôle qualité n'existe que si la chaîne agricole est active.
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
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

    private String createGrade(UserEntity admin, String code, String label, Integer order) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "label": "%s"%s }
                        """.formatted(code, label, order == null ? "" : ", \"sortOrder\": " + order))
                .when().post("/api/v1/quality-grades").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void a_new_tenant_starts_with_an_empty_grid_and_names_its_own_grades() {
        UserEntity a = admin();

        // Aucun grade n'est imposé : personne ne dicte à une filière le
        // vocabulaire d'une autre.
        givenAs(a).when().get("/api/v1/quality-grades")
                .then().statusCode(200).body("data", hasSize(0));

        // Une plantation d'hévéa nomme les siens.
        createGrade(a, "RSS1", "Feuille fumée n°1", 10);
        createGrade(a, "RSS3", "Feuille fumée n°3", 30);

        givenAs(a).when().get("/api/v1/quality-grades")
                .then().statusCode(200)
                .body("data", hasSize(2))
                // Du meilleur au moins bon, tel que la structure l'a rangé.
                .body("data[0].code", equalTo("RSS1"))
                .body("data[1].code", equalTo("RSS3"));
    }

    @Test
    void a_grade_is_stored_as_the_referential_writes_it() {
        UserEntity a = admin();
        createGrade(a, "GR1", "Premier grade", 10);
        LocalDate today = LocalDate.now();

        // Saisi en minuscules, enregistré tel que le référentiel l'écrit :
        // deux orthographes d'un même grade rendraient les états illisibles.
        // La même garde sert au contrôle qualité, qui classe un lot.
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne", "startDate": "%s", "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "gr1", "premiumPerKg": 50 } ] }
                        """.formatted(today))
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .body("data.qualityPremiums[0].grade", equalTo("GR1"));
    }

    @Test
    void a_campaign_premium_is_refused_on_an_unknown_grade() {
        UserEntity a = admin();
        createGrade(a, "GR1", "Premier grade", 10);
        LocalDate today = LocalDate.now();

        // Une prime attachée à un grade qui n'existe pas ne se verse
        // jamais : autant la refuser à la saisie plutôt que de le
        // découvrir au moment de payer un producteur.
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne", "startDate": "%s", "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "SG", "premiumPerKg": 50 } ] }
                        """.formatted(today))
                .when().post("/api/v1/campaigns")
                .then().statusCode(422);

        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne", "startDate": "%s", "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "GR1", "premiumPerKg": 50 } ] }
                        """.formatted(today))
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .body("data.qualityPremiums[0].grade", equalTo("GR1"));
    }

    @Test
    void a_deactivated_grade_can_no_longer_be_assigned() {
        UserEntity a = admin();
        String id = createGrade(a, "GR2", "Second grade", 20);
        LocalDate today = LocalDate.now();

        givenAs(a).when().patch("/api/v1/quality-grades/" + id + "/active?value=false")
                .then().statusCode(200);

        // Désactivé, pas supprimé : l'historique déjà classé le garde, mais
        // on ne l'attribue plus.
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne", "startDate": "%s", "basePricePerKgFcfa": 900,
                          "qualityPremiums": [ { "grade": "GR2", "premiumPerKg": 50 } ] }
                        """.formatted(today))
                .when().post("/api/v1/campaigns")
                .then().statusCode(422);
    }

    @Test
    void two_grades_cannot_share_a_code_whatever_the_case() {
        UserEntity a = admin();
        createGrade(a, "GR1", "Premier grade", 10);

        givenAs(a).contentType("application/json")
                .body("{\"code\":\"gr1\",\"label\":\"Doublon\"}")
                .when().post("/api/v1/quality-grades")
                .then().statusCode(409);
    }
}
