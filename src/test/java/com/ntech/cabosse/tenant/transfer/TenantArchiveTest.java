package com.ntech.cabosse.tenant.transfer;

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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Emporter une structure et la rejouer (backlog SAAS-20 à SAAS-22).
 *
 * <p>Ce que ces tests tiennent, et qu'une archive doit à qui s'en sert
 * pour vérifier ses sauvegardes : elle contient les trois endroits où
 * vit une coopérative, pas seulement sa base ; elle dit d'où elle vient
 * et jusqu'où le schéma était migré ; elle se rejoue en structure neuve
 * sans toucher à l'originale ; et elle reste fermée à qui n'est pas
 * l'éditeur.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantArchiveTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject com.mongodb.client.MongoClient mongoClient;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-arch-" + TestFixtures.randomSlugSuffix(), "Coopérative Archive");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Archive";
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

    private byte[] download(UserEntity platformAdmin, java.util.UUID tenantId) {
        return givenAs(platformAdmin)
                .when().get("/api/v1/admin/tenants/" + tenantId + "/archive")
                .then().statusCode(200)
                .extract().asByteArray();
    }

    @Test
    void l_archive_porte_les_trois_endroits_ou_vit_une_structure() throws Exception {
        UserEntity admin = tenantAdmin();
        // Une donnée d'exploitation, pour que la base ne soit pas vide.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"ZAMACOM\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(201);

        byte[] zip = download(fixtures.createPlatformAdmin(), tenant.id);

        Path tmp = Files.createTempFile("archive", ".zip");
        Files.write(tmp, zip);
        try (ZipFile z = new ZipFile(tmp.toFile())) {
            // La base d'exploitation.
            assertThat(z.getEntry("tenant/customers.jsonl")).isNotNull();
            // La tranche du plan de contrôle : sans les comptes, une
            // sauvegarde restaurée n'a personne pour s'y connecter.
            assertThat(z.getEntry("control/tenants.jsonl")).isNotNull();
            assertThat(z.getEntry("control/users.jsonl")).isNotNull();
            // Le manifeste, qui dit d'où elle vient.
            assertThat(z.getEntry("manifest.json")).isNotNull();
            String manifest = new String(z.getInputStream(z.getEntry("manifest.json"))
                    .readAllBytes());
            assertThat(manifest).contains(tenant.slug);
            // Et ce qu'elle a volontairement laissé de côté : une archive
            // qui se tait sur ses trous laisse croire qu'elle est complète.
            assertThat(manifest).contains("refresh_tokens");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void restaurer_une_structure_deja_presente_est_refuse_avant_d_ecrire() throws Exception {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"GCB\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(201);

        UserEntity platform = fixtures.createPlatformAdmin();
        Path archive = Files.createTempFile("restore", ".zip");
        Files.write(archive, download(platform, tenant.id));

        // Les identifiants sont conservés pour que les pièces jointes et
        // les auteurs restent désignables : ils se heurtent donc sur un
        // serveur qui détient déjà la structure. Le refus arrive avant la
        // moindre écriture, et le nombre de structures ne bouge pas.
        long before = tenantCount(platform);
        givenAs(platform)
                .multiPart("file", new File(archive.toString()))
                .multiPart("slug", "restaure-" + TestFixtures.randomSlugSuffix())
                .when().post("/api/v1/admin/tenants/restore")
                .then().statusCode(409);
        assertThat(tenantCount(platform)).isEqualTo(before);

        // L'originale est intacte.
        givenAs(platform).when().get("/api/v1/admin/tenants/" + tenant.id)
                .then().statusCode(200).body("data.slug", equalTo(tenant.slug));
        Files.deleteIfExists(archive);
    }

    private long tenantCount(UserEntity platform) {
        return ((Number) givenAs(platform).queryParam("perPage", 100)
                .when().get("/api/v1/admin/tenants")
                .then().statusCode(200).extract().path("data.total")).longValue();
    }

    @Test
    void une_archive_se_rejoue_sur_un_serveur_qui_ne_la_detient_pas() throws Exception {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"ZAMACOM\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(201);

        UserEntity platform = fixtures.createPlatformAdmin();
        Path archive = Files.createTempFile("restore", ".zip");
        Files.write(archive, download(platform, tenant.id));

        // On efface la structure d'origine pour figurer le serveur
        // d'arrivée : c'est la situation réelle, vérifier une sauvegarde
        // en local ou déplacer un client vers une autre machine.
        forgetOriginal();

        String newSlug = "restaure-" + TestFixtures.randomSlugSuffix();
        String restoredId = givenAs(platform)
                .multiPart("file", new File(archive.toString()))
                .multiPart("slug", newSlug)
                .when().post("/api/v1/admin/tenants/restore")
                .then().statusCode(201)
                .body("data.slug", equalTo(newSlug))
                .body("data.databaseName", notNullValue())
                .extract().path("data.tenantId");

        // Une identité neuve, et la donnée d'exploitation retrouvée.
        assertThat(restoredId).isNotEqualTo(tenant.id.toString());
        givenAs(platform).when().get("/api/v1/admin/tenants/" + restoredId)
                .then().statusCode(200).body("data.slug", equalTo(newSlug));
        assertThat(mongoClient.getDatabase(
                        "tenant_" + restoredId.replace("-", ""))
                .getCollection("customers").countDocuments()).isEqualTo(1);
        Files.deleteIfExists(archive);
    }

    /** Efface la structure d'origine, comme si l'on changeait de serveur. */
    private void forgetOriginal() {
        var control = mongoClient.getDatabase(
                com.ntech.cabosse.shared.persistence.ControlPlane.DATABASE);
        control.getCollection("tenants")
                .deleteMany(com.mongodb.client.model.Filters.eq("_id", tenant.id));
        for (String c : new String[] { "users", "subscriptions", "support_tickets",
                "cloud_files", "global_audit", "notification_providers" }) {
            control.getCollection(c)
                    .deleteMany(com.mongodb.client.model.Filters.eq("tenantId", tenant.id));
        }
        mongoClient.getDatabase(tenant.databaseName).drop();
    }

    @Test
    void un_raccourci_deja_pris_est_refuse() throws Exception {
        UserEntity admin = tenantAdmin();
        UserEntity platform = fixtures.createPlatformAdmin();
        Path archive = Files.createTempFile("restore", ".zip");
        Files.write(archive, download(platform, tenant.id));

        givenAs(platform)
                .multiPart("file", new File(archive.toString()))
                .multiPart("slug", tenant.slug)
                .when().post("/api/v1/admin/tenants/restore")
                .then().statusCode(409);
        Files.deleteIfExists(archive);
        assertThat(admin).isNotNull();
    }

    @Test
    void l_archive_reste_fermee_a_qui_n_est_pas_l_editeur() {
        UserEntity admin = tenantAdmin();
        // Administrateur de sa propre structure, et pourtant non :
        // l'archive porte des empreintes de mots de passe.
        givenAs(admin).when().get("/api/v1/admin/tenants/" + tenant.id + "/archive")
                .then().statusCode(403);
    }
}
