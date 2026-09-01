package com.ntech.cabosse.notification;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
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
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * La configuration d'envoi propre à une coopérative.
 *
 * <p>Demandée par l'utilisateur le 01/09/2026 : « si un tenant a sa propre
 * config SMTP, SMS, API Brevo, on l'utilise en priorité ; c'est lorsqu'il
 * n'en a pas qu'on utilise celui de la plateforme ». Une structure qui
 * possède son compte envoie sous son domaine plutôt que sous celui de
 * l'éditeur.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantProviderCascadeTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject PlatformSettingsService settings;
    @Inject com.ntech.cabosse.shared.tenant.TenantAwareExecutor executor;
    @Inject com.ntech.cabosse.notification.service.ProviderResolver resolver;

    private TenantEntity lastTenant;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-prv-" + TestFixtures.randomSlugSuffix(), "Coopérative Envoi");
        lastTenant = tenant;
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Envoi";
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

    /** Le socle, tel que le back-office le configure aujourd'hui. */
    private void platformEmail(String host) {
        settings.writeSection("email", Map.of(
                "from", "socle@neiba-technologies.com",
                "host", host,
                "port", "587",
                "mockMode", "false"), Set.of("password"), "test");
        settings.invalidateAll();
    }

    private String declareOwnSmtp(UserEntity who, String label, String host) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "engineCode": "SMTP", "label": "%s", "active": true,
                          "params": { "host": "%s", "port": "587",
                                      "from": "contact@coop.ci" },
                          "usages": [ { "usage": "ALERT", "priority": 1 },
                                      { "usage": "TRANSACTIONAL", "priority": 1 } ] }
                        """.formatted(label, host))
                .when().post("/api/v1/notifications/providers")
                .then().statusCode(201).extract().path("data.id");
    }

    @Test
    void a_cooperative_declares_and_reads_back_its_own_server() {
        UserEntity admin = admin();
        declareOwnSmtp(admin, "SMTP de la coopérative", "smtp.coop.ci");

        // C'est le manque relevé par l'utilisateur : rien ne permettait à
        // un administrateur de structure de déclarer son propre serveur.
        givenAs(admin).when().get("/api/v1/notifications/providers")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].label", equalTo("SMTP de la coopérative"));
    }

    @Test
    void one_cooperative_never_sees_the_server_of_another() {
        UserEntity first = admin();
        declareOwnSmtp(first, "SMTP de la première", "smtp.premiere.ci");

        UserEntity second = admin();

        // Les fournisseurs vivent tous dans le plan de contrôle : sans
        // filtre sur la structure, chacune lirait les identifiants de
        // connexion des autres.
        givenAs(second).when().get("/api/v1/notifications/providers")
                .then().statusCode(200)
                .body("data", hasSize(0));
    }

    @Test
    void a_cooperative_cannot_reach_the_server_of_another_by_its_identifier() {
        UserEntity first = admin();
        String id = declareOwnSmtp(first, "SMTP de la première", "smtp.premiere.ci");

        UserEntity second = admin();

        // Introuvable et non refusé : répondre « interdit » apprendrait
        // déjà que ce fournisseur existe.
        givenAs(second).when().get("/api/v1/notifications/providers/" + id)
                .then().statusCode(404);
    }

    /** Le fournisseur retenu pour cette structure, vu comme le relais le voit. */
    private String resolvedLabelFor(TenantEntity tenant) {
        String[] label = new String[1];
        executor.runForTenant(tenant.id, tenant.databaseName, () -> {
            var usable = resolver.resolve(
                    com.ntech.cabosse.notification.entity.NotificationChannel.EMAIL,
                    com.ntech.cabosse.notification.entity.NotificationUsage.ALERT);
            label[0] = usable.isEmpty() ? null : usable.get(0).provider().label;
        });
        return label[0];
    }

    @Test
    void the_server_of_the_cooperative_comes_before_that_of_the_platform() {
        platformEmail("smtp.socle.ci");
        UserEntity admin = admin();
        TenantEntity mine = lastTenant;
        declareOwnSmtp(admin, "SMTP de la coopérative", "smtp.coop.ci");

        // C'est tout l'objet de la cascade : la structure qui a son compte
        // envoie sous son domaine, et non sous celui de l'éditeur.
        org.assertj.core.api.Assertions.assertThat(resolvedLabelFor(mine))
                .isEqualTo("SMTP de la coopérative");
    }

    @Test
    void a_cooperative_without_a_server_falls_back_on_the_platform() {
        platformEmail("smtp.socle.ci");
        admin();
        TenantEntity mine = lastTenant;

        // Celle qui n'a rien déclaré n'a rien à faire pour être servie.
        org.assertj.core.api.Assertions.assertThat(resolvedLabelFor(mine))
                .isEqualTo("Serveur de la plateforme");
    }

    @Test
    void the_platform_never_lists_what_a_cooperative_declared() {
        UserEntity admin = admin();
        declareOwnSmtp(admin, "SMTP de la coopérative", "smtp.coop.ci");

        // Le back-office administre le socle, pas les comptes de ses
        // clients : les y mêler donnerait à l'éditeur des identifiants
        // qu'on ne lui a pas confiés.
        UserEntity staff = fixtures.createPlatformAdmin(
                "staff-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Test", "Socle");
        givenAs(staff).when().get("/api/v1/admin/notification-providers")
                .then().statusCode(200)
                .body("data.findAll { it.label == 'SMTP de la coopérative' }", hasSize(0));
    }
}
