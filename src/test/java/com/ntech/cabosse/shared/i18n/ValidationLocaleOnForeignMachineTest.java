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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.hamcrest.Matchers.equalTo;

/**
 * Les messages de validation aussi doivent ignorer la langue de la
 * machine.
 *
 * <p>{@code LocaleResolutionTest} vérifie qu'ils suivent l'en-tête
 * {@code Accept-Language}, mais il le vérifie sur un poste de
 * développement en français : la même erreur que celle du 28/08/2026 lui
 * échapperait. Ici, la machine parle anglais, comme le conteneur de
 * production, et le français demandé doit malgré tout être servi.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ValidationLocaleOnForeignMachineTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreMachineLocale() {
        Locale.setDefault(original);
        ResourceBundle.clearCache();
    }

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-loc-" + TestFixtures.randomSlugSuffix(), "Coopérative Locale");
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
    void a_french_speaker_gets_french_from_an_english_machine() {
        UserEntity admin = tenantAdmin();
        Locale.setDefault(Locale.ENGLISH);
        ResourceBundle.clearCache();

        String invalid = "{ \"code\": \"a\", \"name\": \"Cacao\" }";

        // Message de validation, servi par le catalogue Jakarta.
        givenAs(admin).contentType("application/json")
                .header("Accept-Language", "fr")
                .body(invalid)
                .when().post("/api/v1/crops")
                .then().statusCode(400)
                .body("data.fieldErrors[0].message",
                        equalTo("Code culture : lettres, chiffres, tirets"));

        // Message métier, servi par le catalogue applicatif : c'est celui
        // qui basculait en anglais sur le serveur. On le lit sans passer
        // par HTTP, la machine étant déjà en anglais : c'est la résolution
        // du catalogue qu'on éprouve, pas le routage.
        org.junit.jupiter.api.Assertions.assertEquals(
                "Téléphone", Messages.msg(Locale.FRENCH, "m.imp-h-phone"),
                "un en-tête de modèle d'import doit rester français sur une machine anglaise");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Phone", Messages.msg(Locale.ENGLISH, "m.imp-h-phone"));
    }
}
