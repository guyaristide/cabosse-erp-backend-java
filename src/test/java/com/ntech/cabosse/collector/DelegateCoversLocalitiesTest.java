package com.ntech.cabosse.collector;

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
 * Le délégué se rattache aux localités, pas à la section.
 *
 * <p>La règle métier tient en trois phrases : une localité est gérée par
 * <strong>un seul</strong> délégué, un délégué intervient dans plusieurs
 * localités, et un délégué ne peut pas gérer seul une section. Rattaché à
 * une section, on ne pouvait exprimer aucune des trois : impossible de
 * savoir qui collecte dans un village donné, et la dernière règle n'avait
 * même pas de forme.</p>
 *
 * <p>Les deux premières sont opposées à la saisie. La troisième ne peut
 * pas l'être : en remplissant le référentiel on passe forcément par un
 * état où le premier délégué saisi détient tout. Elle devient un contrôle
 * lisible à tout moment.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateCoversLocalitiesTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-loc-" + TestFixtures.randomSlugSuffix(), "Coopérative Localités");
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

    /** Le fournisseur, relu depuis la liste : l'API n'expose pas de lecture unitaire. */
    private io.restassured.path.json.JsonPath supplier(UserEntity admin, String id) {
        return givenAs(admin).when().get("/api/v1/suppliers?perPage=100")
                .then().statusCode(200).extract().jsonPath();
    }

    private String createSection(UserEntity admin, String name) {
        String code = "SEC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"%s\",\"name\":\"%s\"}".formatted(code, name))
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createLocality(UserEntity admin, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "%s"%s }
                        """.formatted(name, sectionId == null ? "" : ", \"sectionId\": \"" + sectionId + "\""))
                .when().post("/api/v1/localities").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name, String... localityIds) {
        String ids = String.join(", ",
                java.util.Arrays.stream(localityIds).map(i -> "\"" + i + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "%s", "collector": true, "localityIds": [%s] }
                        """.formatted("del-" + UUID.randomUUID().toString().substring(0, 6), name, ids))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void a_delegate_covers_several_localities_and_his_section_is_derived() {
        UserEntity a = admin();
        String section = createSection(a, "Section Bangolo");
        String kouibly = createLocality(a, "Kouibly", section);
        String zou = createLocality(a, "Zou", section);

        String delegate = createDelegate(a, "KONE Adama", kouibly, zou);

        var found = supplier(a, delegate);
        String path = "data.items.find { it.id == '" + delegate + "' }";
        org.junit.jupiter.api.Assertions.assertEquals(2, found.getList(path + ".localityIds").size());
        // La section n'est pas saisie : elle se déduit des localités.
        org.junit.jupiter.api.Assertions.assertEquals(section, found.getString(path + ".sectionId"));
    }

    @Test
    void a_locality_is_managed_by_one_delegate_only() {
        UserEntity a = admin();
        String section = createSection(a, "Section Bangolo");
        String kouibly = createLocality(a, "Kouibly", section);
        String zou = createLocality(a, "Zou", section);

        createDelegate(a, "KONE Adama", kouibly);

        // Deux délégués sur le même village compteraient deux fois la même
        // collecte et rendraient indécidable à qui rattacher un producteur.
        givenAs(a).contentType("application/json")
                .body("""
                        { "code": "del-x", "name": "TRAORE Salif", "collector": true,
                          "localityIds": ["%s", "%s"] }
                        """.formatted(zou, kouibly))
                .when().post("/api/v1/suppliers")
                .then().statusCode(409);

        // Sur un village libre, il passe.
        givenAs(a).contentType("application/json")
                .body("""
                        { "code": "del-y", "name": "TRAORE Salif", "collector": true,
                          "localityIds": ["%s"] }
                        """.formatted(zou))
                .when().post("/api/v1/suppliers").then().statusCode(201);
    }

    @Test
    void a_delegate_straddling_two_sections_belongs_to_neither() {
        UserEntity a = admin();
        String nord = createSection(a, "Section Nord");
        String sud = createSection(a, "Section Sud");
        String kouibly = createLocality(a, "Kouibly", nord);
        String zou = createLocality(a, "Zou", sud);

        String delegate = createDelegate(a, "BAMBA Sekou", kouibly, zou);

        // Forcer l'une des deux mentirait sur tous les états qui trient par
        // section : mieux vaut aucune section qu'une section fausse.
        String path = "data.items.find { it.id == '" + delegate + "' }";
        org.junit.jupiter.api.Assertions.assertNull(supplier(a, delegate).getString(path + ".sectionId"));
    }

    @Test
    void the_coverage_control_names_the_villages_nobody_collects_in() {
        UserEntity a = admin();
        String section = createSection(a, "Section Bangolo");
        String kouibly = createLocality(a, "Kouibly", section);
        createLocality(a, "Zou", section);
        createLocality(a, "Village sans section", null);

        createDelegate(a, "KONE Adama", kouibly);

        var coverage = givenAs(a).when().get("/api/v1/sections/coverage")
                .then().statusCode(200)
                .body("data.sections", hasSize(1))
                .extract().jsonPath();

        // Une section de deux villages, un seul couvert : le second est
        // nommé plutôt que compté, pour qu'on sache où aller.
        org.junit.jupiter.api.Assertions.assertEquals(
                2, coverage.getInt("data.sections[0].localityCount"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Zou", coverage.getString("data.sections[0].uncoveredLocalities[0].name"));
        // Une localité sans section échappe à tout contrôle : elle est dite.
        org.junit.jupiter.api.Assertions.assertEquals(
                "Village sans section", coverage.getString("data.unassignedLocalities[0].name"));
    }

    @Test
    void a_section_held_by_a_single_delegate_is_flagged_without_being_refused() {
        UserEntity a = admin();
        String section = createSection(a, "Section Bangolo");
        String kouibly = createLocality(a, "Kouibly", section);
        String zou = createLocality(a, "Zou", section);

        // Un seul délégué sur toute la section : contraire à la règle, mais
        // refuser l'enregistrement rendrait le référentiel impossible à
        // remplir, le premier délégué saisi détenant forcément tout.
        createDelegate(a, "KONE Adama", kouibly, zou);

        givenAs(a).when().get("/api/v1/sections/coverage")
                .then().statusCode(200)
                .body("data.sections[0].heldByASingleDelegate", equalTo(true))
                .body("data.sections[0].delegates", hasSize(1));

        // À deux, le signalement tombe.
        String autre = createLocality(a, "Guézon", section);
        createDelegate(a, "TRAORE Salif", autre);
        givenAs(a).when().get("/api/v1/sections/coverage")
                .then().statusCode(200)
                .body("data.sections[0].heldByASingleDelegate", equalTo(false))
                .body("data.sections[0].delegates", hasSize(2));
    }

    @Test
    void a_delegate_without_localities_keeps_the_section_he_was_given() {
        UserEntity a = admin();
        String section = createSection(a, "Section Bangolo");

        // Les structures qui n'ont pas encore rangé leurs villages
        // continuent de travailler : la section saisie fait foi tant
        // qu'aucune localité n'est rattachée.
        String delegate = givenAs(a).contentType("application/json")
                .body("""
                        { "code": "del-z", "name": "DIABATE Moussa", "collector": true,
                          "sectionId": "%s" }
                        """.formatted(section))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        var found = supplier(a, delegate);
        String path = "data.items.find { it.id == '" + delegate + "' }";
        org.junit.jupiter.api.Assertions.assertEquals(section, found.getString(path + ".sectionId"));
        org.junit.jupiter.api.Assertions.assertEquals(0, found.getList(path + ".localityIds").size());
    }
}
