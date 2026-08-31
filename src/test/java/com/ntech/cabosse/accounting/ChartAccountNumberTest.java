package com.ntech.cabosse.accounting;

import com.ntech.cabosse.accounting.entity.AccountFamily;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * La longueur d'un numéro de compte.
 *
 * <p>Le plan tient en six chiffres, sous-comptes compris : {@code 471100}
 * débiteurs divers, {@code 471110} débiteurs divers-délégués. Mais un
 * compte rattaché à un tiers descend plus loin, {@code 47111001} pour le
 * premier délégué, et la contrainte à huit caractères l'arrêtait net.</p>
 *
 * <p>Une chose ne bouge pas : le numéro reste une suite de chiffres. Le
 * premier donne la classe SYSCOHADA, et le tri comme le rattachement
 * lisent le reste comme une suite de rangs. Les noms des tiers vivent
 * dans l'intitulé, jamais dans le numéro.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ChartAccountNumberTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-plan-" + TestFixtures.randomSlugSuffix(), "Coopérative Plan");
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

    private io.restassured.response.ValidatableResponse create(
            UserEntity who, String number, String label) {
        return givenAs(who).contentType("application/json")
                .body("{ \"number\": \"%s\", \"label\": \"%s\" }".formatted(number, label))
                .when().post("/api/v1/accounting/chart").then();
    }

    @Test
    void the_chain_of_the_expert_is_accepted_end_to_end() {
        UserEntity admin = admin();
        // Le compte principal, son sous-compte, puis un compte par délégué.
        create(admin, "471100", "Débiteurs divers").statusCode(201);
        create(admin, "471110", "Débiteurs divers-délégués").statusCode(201);
        create(admin, "47111001", "Kouakou").statusCode(201);
        create(admin, "47111002", "Yao").statusCode(201);
        create(admin, "47111003", "Konan").statusCode(201);
    }

    @Test
    void a_deep_account_stays_in_its_class() {
        UserEntity admin = admin();
        create(admin, "47111004", "Brou").statusCode(201)
                // La classe se lit du premier chiffre, quelle que soit la
                // profondeur : un compte de tiers reste un compte de tiers.
                .body("data.family", equalTo(AccountFamily.TIERS.name()));
    }

    @Test
    void twenty_characters_are_allowed_and_twenty_one_are_not() {
        UserEntity admin = admin();
        create(admin, "4".repeat(20), "Compte très profond").statusCode(201);
        create(admin, "4".repeat(21), "Un caractère de trop").statusCode(400);
    }

    @Test
    void letters_stay_out_of_the_number() {
        UserEntity admin = admin();
        // Sans chiffre en tête, la classe SYSCOHADA ne se déduit plus.
        create(admin, "A71100", "Compte sans classe").statusCode(400);
        // Et une lettre au milieu casserait le tri comme le rattachement,
        // qui lisent le numéro comme une suite de rangs. Le nom du tiers
        // est dans l'intitulé, pas dans le numéro.
        create(admin, "47A100", "Compte avec lettre").statusCode(400);
    }

    @Test
    void spaces_and_punctuation_stay_out() {
        UserEntity admin = admin();
        create(admin, "471 100", "Avec espace").statusCode(400);
        create(admin, "471-100", "Avec tiret").statusCode(400);
    }

    @Test
    void the_referential_reads_the_plan_without_the_balances() {
        UserEntity admin = admin();
        create(admin, "471120", "Débiteurs divers-clients").statusCode(201);

        // Le référentiel n'a rien à dire des soldes : il ne paie donc pas
        // une passe sur tout le journal pour afficher une liste de comptes.
        List<Object> numbers = givenAs(admin)
                .when().get("/api/v1/accounting/chart?withBalances=false")
                .then().statusCode(200)
                .extract().path("data.number");
        assertThat(numbers).contains("471120");
    }
}
