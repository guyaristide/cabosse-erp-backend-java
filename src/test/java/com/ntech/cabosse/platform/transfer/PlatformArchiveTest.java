package com.ntech.cabosse.platform.transfer;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sauvegarder toute la plateforme, et la remonter.
 *
 * <p>L'archive d'une structure sert à déplacer un client. Celle-ci sert à
 * remonter un serveur après incident : elle emporte le plan de contrôle
 * entier, la base de chaque structure et tous les binaires, sans rien
 * filtrer. Une sauvegarde qui choisit ce qu'elle garde ne répond plus de
 * ce qu'elle rend.</p>
 *
 * <p>Sa restauration est le geste le plus destructeur du produit : elle
 * efface l'état de tous les clients à la fois, y compris les comptes qui
 * l'autorisent. Le rôle plateforme ne suffit donc pas, et ces tests
 * tiennent la seconde barrière : un secret posé sur le serveur, hors de
 * l'application.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PlatformArchiveTest extends AbstractIntegrationTest {

    /** Un PNG minimal valide, suffisant pour que l'envoi soit accepté. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-plt-" + TestFixtures.randomSlugSuffix(), "Coopérative Plateforme");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Plateforme";
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

    private Path download(UserEntity platformAdmin) throws Exception {
        byte[] zip = givenAs(platformAdmin)
                .when().get("/api/v1/admin/platform/archive")
                .then().statusCode(200)
                .extract().asByteArray();
        Path tmp = Files.createTempFile("plateforme", ".zip");
        Files.write(tmp, zip);
        return tmp;
    }

    @Test
    void la_sauvegarde_emporte_le_plan_de_controle_les_structures_et_les_fichiers() throws Exception {
        UserEntity admin = tenantAdmin();
        UserEntity platform = fixtures.createPlatformAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"ZAMACOM\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(201);
        givenAs(platform).multiPart("logo", "logo.png", PNG, "image/png")
                .when().put("/api/v1/admin/tenants/" + tenant.id + "/logo")
                .then().statusCode(204);

        Path archive = download(platform);
        try (ZipFile z = new ZipFile(archive.toFile())) {
            // Le plan de contrôle entier, pas une tranche.
            assertThat(z.getEntry("control/tenants.jsonl")).isNotNull();
            assertThat(z.getEntry("control/users.jsonl")).isNotNull();
            // La base de la structure, rangée sous son nom de base.
            assertThat(z.getEntry(
                    "tenants/" + tenant.databaseName + "/customers.jsonl")).isNotNull();
            // Et les binaires.
            long binaries = z.stream()
                    .filter(e -> e.getName().startsWith("files/") && !e.isDirectory())
                    .count();
            assertThat(binaries).isGreaterThanOrEqualTo(1);

            String manifest = new String(z.getInputStream(z.getEntry("manifest.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains(tenant.slug);
            // Ce qu'elle laisse de côté, dit franchement : une archive qui
            // se tait sur ses trous laisse croire qu'elle est complète.
            assertThat(manifest).contains("refresh_tokens");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void la_restauration_complete_refuse_sans_le_secret_du_serveur() throws Exception {
        UserEntity platform = fixtures.createPlatformAdmin();
        tenantAdmin();
        Path archive = download(platform);
        try {
            // Le rôle plateforme ne suffit pas : il est porté par des
            // comptes de travail, et la restauration efface précisément
            // les comptes qui l'autorisent.
            givenAs(platform)
                    .multiPart("file", "plateforme.zip", Files.readAllBytes(archive),
                            "application/zip")
                    .when().post("/api/v1/admin/platform/archive/restore")
                    .then().statusCode(403);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void un_mauvais_secret_est_refuse_comme_un_secret_absent() throws Exception {
        UserEntity platform = fixtures.createPlatformAdmin();
        tenantAdmin();
        Path archive = download(platform);
        try {
            givenAs(platform)
                    .header("X-Platform-Restore-Secret", "ce-n-est-pas-le-bon")
                    .multiPart("file", "plateforme.zip", Files.readAllBytes(archive),
                            "application/zip")
                    .when().post("/api/v1/admin/platform/archive/restore")
                    .then().statusCode(403);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void la_sauvegarde_reste_fermee_a_qui_n_est_pas_l_editeur() {
        UserEntity admin = tenantAdmin();

        // Elle contient les données de tous les clients : un administrateur
        // de coopérative n'a rien à y voir, fût-ce la sienne.
        givenAs(admin).when().get("/api/v1/admin/platform/archive")
                .then().statusCode(403);
    }
}
