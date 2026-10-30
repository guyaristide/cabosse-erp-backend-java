package com.ntech.cabosse.importjournal;

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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le fichier déposé à un import est gardé, retéléchargeable et emporté
 * par l'archive de la structure (DEC-52, 30/10/2026).
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ImportFileTest extends AbstractIntegrationTest {

    private static final byte[] CSV = "code;nom\nM-001;KOUAME Awa\n".getBytes();

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-impf-" + TestFixtures.randomSlugSuffix(), "Coopérative Fichiers");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Fichiers";
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
    void the_deposited_file_is_kept_listed_downloadable_and_archived() throws Exception {
        UserEntity admin = admin();

        String id = givenAs(admin)
                .multiPart("file", "membres.csv", CSV, "text/csv")
                .multiPart("domain", "members")
                .multiPart("rowsCreated", "1")
                .multiPart("rowsRejected", "0")
                .when().post("/api/v1/import-files")
                .then().statusCode(201)
                .body("data.domain", equalTo("members"))
                .body("data.fileName", equalTo("membres.csv"))
                .body("data.rowsCreated", equalTo(1))
                .extract().path("data.id");

        givenAs(admin).when().get("/api/v1/import-files?domain=members")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].id", equalTo(id));

        byte[] back = givenAs(admin).when().get("/api/v1/import-files/" + id + "/content")
                .then().statusCode(200)
                .header("Content-Disposition", org.hamcrest.Matchers.containsString("membres.csv"))
                .extract().asByteArray();
        assertArrayEquals(CSV, back);

        // L'archive complète de la structure emporte le fichier déposé.
        UserEntity platform = fixtures.createPlatformAdmin();
        byte[] zip = givenAs(platform).when().get("/api/v1/admin/tenants/" + tenant.id + "/archive")
                .then().statusCode(200).extract().asByteArray();
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().startsWith("files/")) {
                    byte[] content = in.readAllBytes();
                    if (java.util.Arrays.equals(content, CSV)) found = true;
                }
            }
        }
        assertTrue(found, "le fichier d'import n'est pas dans l'archive");
    }
}
