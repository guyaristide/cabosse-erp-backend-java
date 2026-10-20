package com.ntech.cabosse.accounting;

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

import static org.hamcrest.Matchers.equalTo;

/**
 * Le prochain sous-compte d'une racine (demandé le 19/10/2026).
 *
 * <p>Le comptable ouvrait le compte nominatif d'un délégué en allant
 * chercher au plan le dernier numéro posé sous la racine pour taper le
 * suivant. La racine dit où l'on est, le plan dit où l'on en est : le
 * rang suivant est le plus grand déjà posé plus un, jamais un trou
 * laissé entre deux.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SubAccountNumberingTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-sub-" + TestFixtures.randomSlugSuffix(), "Coopérative Sous-comptes");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Plan";
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

    private void create(UserEntity who, String number, String label) {
        givenAs(who).contentType("application/json")
                .body("{ \"number\": \"%s\", \"label\": \"%s\" }".formatted(number, label))
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
    }

    /** Les deux racines de l'expert, posées si le plan du tenant d'essai ne les sème pas. */
    private void roots(UserEntity who) {
        for (String[] r : new String[][] {
                { "409100", "Avances aux délégués collecteurs" },
                { "409200", "Créances sur producteurs : crédits et avances" } }) {
            givenAs(who).contentType("application/json")
                    .body("{ \"number\": \"%s\", \"label\": \"%s\" }".formatted(r[0], r[1]))
                    .when().post("/api/v1/accounting/chart");
        }
    }

    private String next(UserEntity who, String root) {
        return givenAs(who).when().get("/api/v1/accounting/chart/sub-accounts/next?root=" + root)
                .then().statusCode(200).extract().path("data.number");
    }

    private String open(UserEntity who, String root, String label) {
        return givenAs(who).contentType("application/json")
                .body("{ \"root\": \"%s\", \"label\": \"%s\" }".formatted(root, label))
                .when().post("/api/v1/accounting/chart/sub-accounts")
                .then().statusCode(201).extract().path("data.number");
    }

    @Test
    void the_first_sub_account_of_a_root_takes_the_rank_one() {
        UserEntity a = admin();
        roots(a);
        // Sous 409100, avances aux délégués, le premier sous-compte est 409101.
        org.junit.jupiter.api.Assertions.assertEquals("409101", next(a, "409100"));
        org.junit.jupiter.api.Assertions.assertEquals("409101", open(a, "409100", "Délégué KONAN Yao"));
        org.junit.jupiter.api.Assertions.assertEquals("409102", open(a, "409100", "Délégué DIABY Jean"));
    }

    @Test
    void the_next_rank_follows_the_highest_one_posed_never_a_gap() {
        UserEntity a = admin();
        roots(a);
        create(a, "409205", "Producteur posé à la main");
        // 409201 à 409204 sont libres : on ne les reprend pas, le rang
        // suivant est 409206.
        org.junit.jupiter.api.Assertions.assertEquals("409206", next(a, "409200"));
    }

    @Test
    void a_ten_digit_root_keeps_its_own_width() {
        UserEntity a = admin();
        create(a, "4091100000", "Avances aux délégués collecteurs");
        create(a, "4091100105", "Délégué cent cinq");
        org.junit.jupiter.api.Assertions.assertEquals("4091100106", next(a, "4091100000"));
    }

    @Test
    void the_number_lengthens_when_the_positions_are_all_taken() {
        UserEntity a = admin();
        create(a, "471100", "Débiteurs divers");
        create(a, "471199", "Dernier rang à deux positions");
        org.junit.jupiter.api.Assertions.assertEquals("4711001", next(a, "471100"));
    }

    @Test
    void an_unknown_root_is_refused() {
        UserEntity a = admin();
        givenAs(a).when().get("/api/v1/accounting/chart/sub-accounts/next?root=999999")
                .then().statusCode(422);
        givenAs(a).contentType("application/json")
                .body("{ \"root\": \"999999\", \"label\": \"Nulle part\" }")
                .when().post("/api/v1/accounting/chart/sub-accounts")
                .then().statusCode(422);
    }

    @Test
    void the_opened_account_is_a_tenant_account_of_the_root_family() {
        UserEntity a = admin();
        roots(a);
        String number = open(a, "409100", "Délégué TUO Serge");
        givenAs(a).when().get("/api/v1/accounting/chart?withBalances=false")
                .then().statusCode(200)
                .body("data.find { it.number == '" + number + "' }.label", equalTo("Délégué TUO Serge"))
                .body("data.find { it.number == '" + number + "' }.system", equalTo(false));
    }

    /** Un sociétaire avec son fournisseur miroir, créé par la voie ordinaire. */
    private String member(UserEntity who, String lastName, String firstName) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "lastName": "%s", "firstName": "%s", "gender": "MALE", "status": "ACTIVE" }
                        """.formatted(lastName, firstName))
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void every_member_gets_a_supplier_account_in_one_go_and_the_pass_is_replayable() {
        UserEntity a = admin();
        // Les sociétaires suivent la capacité du modèle coopératif.
        TenantEntity tenant = tenants.findById(a.tenantId);
        tenant.organizationModel = com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        create(a, "401110", "Fournisseurs : producteurs membres");
        String first = member(a, "KONAN", "Yao");
        member(a, "DIABY", "Jean");

        givenAs(a).when().get("/api/v1/accounting/chart/sub-accounts/members/pending")
                .then().statusCode(200).body("data.withoutAccount", equalTo(2));

        givenAs(a).contentType("application/json").body("{ \"root\": \"401110\" }")
                .when().post("/api/v1/accounting/chart/sub-accounts/members")
                .then().statusCode(200)
                .body("data.opened", equalTo(2)).body("data.alreadyOpen", equalTo(0));

        // Chaque miroir porte son compte, intitulé « Producteur » suivi du nom.
        givenAs(a).when().get("/api/v1/members/" + first)
                .then().statusCode(200).body("data.supplierId", org.hamcrest.Matchers.notNullValue());
        // Les deux miroirs portent 401111 et 401112, dans l'ordre où le
        // registre les a rendus.
        givenAs(a).when().get("/api/v1/suppliers?q=KONAN")
                .then().statusCode(200)
                .body("data.items[0].subsidiaryAccount", org.hamcrest.Matchers.matchesPattern("40111[12]"));
        givenAs(a).when().get("/api/v1/accounting/chart?withBalances=false")
                .then().statusCode(200)
                .body("data.findAll { it.number ==~ /40111[12]/ }.label",
                        org.hamcrest.Matchers.containsInAnyOrder("Producteur KONAN Yao", "Producteur DIABY Jean"));

        // Rejouer n'ouvre rien de plus.
        givenAs(a).contentType("application/json").body("{ \"root\": \"401110\" }")
                .when().post("/api/v1/accounting/chart/sub-accounts/members")
                .then().statusCode(200)
                .body("data.opened", equalTo(0)).body("data.alreadyOpen", equalTo(2));
    }
}
