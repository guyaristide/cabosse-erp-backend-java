package com.ntech.cabosse.shared.i18n;

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
 * La langue des messages suit l'en-tête {@code Accept-Language} : français
 * par défaut, anglais quand le front le demande, retour au français pour
 * toute langue non servie. Un seul mécanisme pour les validations Jakarta
 * (catalogues ValidationMessages) et les messages métier (Messages).
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class LocaleResolutionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-i18n-" + TestFixtures.randomSlugSuffix(), "Coopérative i18n");
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
    void a_validation_message_follows_the_request_language() {
        UserEntity admin = tenantAdmin();
        String invalid = "{ \"code\": \"a\", \"name\": \"Cacao\" }";

        // Sans en-tête : français, la langue de référence.
        givenAs(admin).contentType("application/json").body(invalid)
                .when().post("/api/v1/crops")
                .then().statusCode(400)
                .body("data.fieldErrors[0].message",
                        equalTo("Code culture : lettres, chiffres, tirets"));

        givenAs(admin).contentType("application/json")
                .header("Accept-Language", "en")
                .body(invalid)
                .when().post("/api/v1/crops")
                .then().statusCode(400)
                .body("data.fieldErrors[0].message",
                        equalTo("Crop code: letters, digits, hyphens"));

        // Langue non servie : retour au français, jamais une clé brute.
        givenAs(admin).contentType("application/json")
                .header("Accept-Language", "es")
                .body(invalid)
                .when().post("/api/v1/crops")
                .then().statusCode(400)
                .body("data.fieldErrors[0].message",
                        equalTo("Code culture : lettres, chiffres, tirets"));
    }
}
