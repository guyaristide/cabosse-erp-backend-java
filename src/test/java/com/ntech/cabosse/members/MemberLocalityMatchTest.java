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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Le village d'un producteur se rattache au référentiel des localités.
 *
 * <p>Tant qu'il n'était qu'une chaîne recopiée, on ne pouvait pas savoir
 * quel délégué collecte chez un producteur, alors qu'une localité est
 * gérée par un seul délégué. Le rattachement devait donc être ressaisi à
 * la main, avec une occasion de plus de se contredire.</p>
 *
 * <p>Le rapprochement distingue trois cas et refuse d'en confondre deux.
 * Identique, on rattache. Rien de proche, on crée. <strong>Ressemblant,
 * on ne tranche pas</strong> : rattacher au plus proche fusionnerait
 * « Kouibly » et « Kouibli » sans que personne ne l'ait voulu, et une
 * fusion ne se défait pas.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberLocalityMatchTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-vil-" + TestFixtures.randomSlugSuffix(), "Coopérative Villages");
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

    private String createLocality(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"%s\"}".formatted(name))
                .when().post("/api/v1/localities").then().statusCode(201).extract().path("data.id");
    }

    /** Une ligne d'import portant un village. */
    private static String row(int n, String card, String lastName, String village, String localityId) {
        return """
                { "rowNumber": %d, "externalCode": "%s", "lastName": "%s", "firstName": "Yao",
                  "gender": "M", "village": "%s"%s }
                """.formatted(n, card, lastName, village,
                localityId == null ? "" : ", \"localityId\": \"" + localityId + "\"");
    }

    @Test
    void an_identical_village_is_linked_without_asking() {
        UserEntity a = admin();
        String kouibly = createLocality(a, "Kouibly");

        // Accents, casse et ponctuation ne font pas une différence.
        String payload = "[" + row(1, "CCC-1", "Kouassi", "KOUIBLY", null) + "]";
        var preview = givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .extract().jsonPath();

        org.junit.jupiter.api.Assertions.assertEquals(
                "EXACT", preview.getString("data.rows[0].localityMatch.status"));
        org.junit.jupiter.api.Assertions.assertEquals(
                kouibly, preview.getString("data.rows[0].localityMatch.localityId"));
    }

    @Test
    void an_unknown_village_is_created() {
        UserEntity a = admin();
        String payload = "[" + row(1, "CCC-2", "Bamba", "Zoukougbeu", null) + "]";

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.rows[0].localityMatch.status", equalTo("NEW"))
                .body("data.readyRows", equalTo(1));

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(1));

        // La localité est entrée au référentiel, et le producteur y pointe.
        var locs = givenAs(a).when().get("/api/v1/localities")
                .then().statusCode(200).extract().jsonPath();
        String created = locs.getString("data.find { it.name == 'Zoukougbeu' }.id");
        org.junit.jupiter.api.Assertions.assertNotNull(created);

        var members = givenAs(a).when().get("/api/v1/members?perPage=50")
                .then().statusCode(200).extract().jsonPath();
        org.junit.jupiter.api.Assertions.assertEquals(
                created, members.getString("data.items[0].localityId"));
    }

    @Test
    void a_similar_village_is_proposed_and_never_applied_by_itself() {
        UserEntity a = admin();
        String kouibly = createLocality(a, "Kouibly");

        // « Kouibli » ressemble à « Kouibly » sans être lui : une lettre
        // d'écart. Trancher fusionnerait deux villages, et une fusion ne
        // se défait pas.
        String payload = "[" + row(1, "CCC-3", "Traoré", "Kouibli", null) + "]";
        var preview = givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                // La ligne est écartée : elle attend une réponse.
                .body("data.invalidRows", equalTo(1))
                .body("data.readyRows", equalTo(0))
                .body("data.rows[0].localityMatch.status", equalTo("SIMILAR"))
                .body("data.rows[0].localityMatch.candidates", hasSize(1))
                .body("data.rows[0].issues[0].field", equalTo("village"))
                .extract().jsonPath();

        org.junit.jupiter.api.Assertions.assertEquals(
                "Kouibly", preview.getString("data.rows[0].localityMatch.candidates[0].name"));

        // La réponse de l'utilisateur clôt la question : la ligne passe.
        String answered = "[" + row(1, "CCC-3", "Traoré", "Kouibli", kouibly) + "]";
        givenAs(a).contentType("application/json").body(answered)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.rows[0].localityMatch.status", equalTo("EXACT"))
                .body("data.rows[0].localityMatch.localityId", equalTo(kouibly));
    }

    @Test
    void the_delegate_of_a_producer_is_read_from_his_village() {
        UserEntity a = admin();
        String kouibly = createLocality(a, "Kouibly");

        String delegate = givenAs(a).contentType("application/json")
                .body("""
                        { "code": "del-1", "name": "KONE Adama", "collector": true,
                          "localityIds": ["%s"] }
                        """.formatted(kouibly))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        String memberId = givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "Kouassi", "gender": "MALE", "status": "ACTIVE",
                          "village": "Kouibly", "localityId": "%s" }
                        """.formatted(kouibly))
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        // Le lien n'est pas saisi : il se lit. Le stocker ouvrirait la porte
        // à deux réponses pour une seule réalité.
        givenAs(a).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.delegateSupplierId", equalTo(delegate))
                .body("data.delegateName", equalTo("KONE Adama"));
    }

    @Test
    void the_village_name_follows_the_locality_it_points_to() {
        UserEntity a = admin();
        String kouibly = createLocality(a, "Kouibly");

        // Le nom affiché sur la fiche est celui du référentiel, pas celui
        // que le fichier a écrit : deux orthographes pour un même village
        // rendraient les registres illisibles.
        String memberId = givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "Kouassi", "gender": "MALE", "status": "ACTIVE",
                          "village": "kouibli", "localityId": "%s" }
                        """.formatted(kouibly))
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        givenAs(a).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.village", equalTo("Kouibly"));
    }

    @Test
    void a_producer_without_a_village_keeps_no_locality_and_no_delegate() {
        UserEntity a = admin();
        String memberId = givenAs(a).contentType("application/json")
                .body("{\"lastName\":\"Sans village\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        givenAs(a).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.localityId", equalTo(null))
                .body("data.delegateSupplierId", equalTo(null));
    }
}
