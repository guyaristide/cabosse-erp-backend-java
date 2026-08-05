package com.ntech.cabosse.supplier;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Prévention des doublons au registre des fournisseurs.
 *
 * <p>Sans codification fiable, le même apporteur finit enregistré deux
 * fois, et les synthèses de fin de campagne comptent deux fournisseurs à
 * moitié chacun. Ce que le système doit faire : signaler ce qui existe
 * déjà, dire pourquoi, et laisser trancher.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupplierDuplicateTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dbl-" + TestFixtures.randomSlugSuffix(), "Coopérative Doublons");
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

    /** Création assumée : le doublon a été regardé et écarté. */
    private String create(UserEntity admin, String name, String phone, String city) {
        return givenAs(admin).contentType("application/json")
                .queryParam("confirmDuplicate", true)
                .body("""
                        { "name": "%s", "phone": "%s", "cityName": "%s" }
                        """.formatted(name, phone, city))
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void the_same_name_written_differently_is_flagged() {
        UserEntity admin = tenantAdmin();
        create(admin, "Kouassi Yao", "0707010203", "Bangolo");

        // Prénom et nom inversés : une comparaison caractère à caractère
        // les éloignerait, la coopérative y voit la même personne.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Yao Kouassi\", \"cityName\": \"Bangolo\" }")
                .when().post("/api/v1/suppliers")
                .then().statusCode(409)
                .body("statusMessage", containsString("Kouassi Yao"))
                .body("data", hasSize(1))
                .body("data[0].matchedOn", hasItem("nameExact"));
    }

    @Test
    void the_phone_number_alone_raises_the_alert() {
        UserEntity admin = tenantAdmin();
        create(admin, "Coulibaly Adama", "+225 07 07 01 02 03", "Duékoué");

        // Numéro identique saisi sans indicatif, nom sans rapport : deux
        // personnes ne partagent pas un numéro.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Groupement Espoir\", \"phone\": \"0707010203\" }")
                .when().post("/api/v1/suppliers")
                .then().statusCode(409)
                .body("data[0].matchedOn", hasItem("phone"))
                .body("data[0].name", equalTo("Coulibaly Adama"));
    }

    @Test
    void an_unrelated_supplier_goes_through() {
        UserEntity admin = tenantAdmin();
        create(admin, "Kouassi Yao", "0707010203", "Bangolo");

        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Societe Ivoire Cacao\", \"phone\": \"0505998877\" }")
                .when().post("/api/v1/suppliers")
                .then().statusCode(201);
    }

    @Test
    void a_shared_family_name_needs_the_locality_to_alert() {
        UserEntity admin = tenantAdmin();
        create(admin, "Bamba Salif", "0101010101", "Man");

        // Même nom de famille, prénom différent, autre village : deux
        // frères peuvent exister, on ne dérange pas la saisie.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Bamba Ibrahim\", \"cityName\": \"Guiglo\" }")
                .when().post("/api/v1/suppliers")
                .then().statusCode(201);

        // Même nom de famille et même village : cette fois, on demande.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Bamba Moussa\", \"cityName\": \"Man\" }")
                .when().post("/api/v1/suppliers")
                .then().statusCode(409)
                .body("data[0].matchedOn", hasItem("city"));
    }

    @Test
    void the_check_can_be_consulted_before_submitting() {
        UserEntity admin = tenantAdmin();
        String existing = create(admin, "Traore Mamadou", "0102030405", "Bangolo");

        givenAs(admin).queryParam("name", "Traoré Mamadou")
                .when().get("/api/v1/suppliers/duplicates")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].id", equalTo(existing));

        // Une fiche ne se propose pas comme son propre doublon.
        givenAs(admin).queryParam("name", "Traoré Mamadou").queryParam("excludeId", existing)
                .when().get("/api/v1/suppliers/duplicates")
                .then().statusCode(200)
                .body("data", hasSize(0));
    }
}
