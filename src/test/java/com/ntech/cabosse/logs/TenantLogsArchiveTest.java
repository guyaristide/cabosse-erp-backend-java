package com.ntech.cabosse.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.trace.RequestTraceFilter;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * L'archive des journaux d'une structure (SEC-16, décision DEC-51).
 *
 * <p>Une structure contrôlée doit pouvoir emporter tout ce que le serveur
 * a retenu d'elle sur une période : les décisions du journal d'audit et
 * les requêtes qui les ont produites. Le test fait deux écritures, puis
 * demande l'archive et vérifie qu'elles y sont, avec leur statut.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantLogsArchiveTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject ObjectMapper json;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-logs-" + TestFixtures.randomSlugSuffix(), "Coopérative Journaux");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user(Roles.TENANT_ADMIN, "admin");
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Test";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    /** Un collaborateur ordinaire, porteur des seuls droits nommés. */
    private UserEntity withRights(UserEntity admin, String prefix, String... permissions) {
        UserEntity u = user(Roles.USER, prefix);
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }"
                        .formatted(prefix + "-" + TestFixtures.randomSlugSuffix(), perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id)
                .then().statusCode(204);
        return u;
    }

    /** Deux écritures ordinaires : un site et un article. */
    private void twoWrites(UserEntity admin) {
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"m-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201)
                // Chaque réponse porte son identifiant de corrélation.
                .header(RequestTraceFilter.REQUEST_ID_HEADER, notNullValue());
        givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201);
    }

    private static String yesterday() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    }

    private static String tomorrow() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void the_archive_carries_the_writes_of_the_period() throws IOException {
        UserEntity admin = admin();
        twoWrites(admin);

        var response = givenAs(admin)
                .queryParam("from", yesterday()).queryParam("to", tomorrow())
                .when().get("/api/v1/me/tenant/admin/logs/archive")
                .then().statusCode(200)
                .contentType("application/zip")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, notNullValue())
                .header("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "journaux-" + tenant.slug + "-"));

        Map<String, String> entries = unzip(response.extract().asByteArray());
        assertThat(entries.keySet()).contains(
                "manifest.json",
                "audit-events.csv", "audit-events.jsonl",
                "request-traces.csv", "request-traces.jsonl",
                "import-runs.csv", "import-runs.jsonl",
                "notifications.csv", "notifications.jsonl",
                "idempotency.csv");

        // La création du site est tracée, avec le statut rendu.
        String traces = entries.get("request-traces.csv");
        assertThat(traces.lines().filter(l -> l.contains(",POST,/api/v1/sites,"))
                .anyMatch(l -> l.contains(",201,OK,"))).isTrue();
        assertThat(traces).contains(admin.email);

        // Au moins un événement d'audit sur la période, en-tête exclu.
        assertThat(entries.get("audit-events.csv").lines().count()).isGreaterThan(1);

        JsonNode manifest = json.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("tenantSlug").asText()).isEqualTo(tenant.slug);
        assertThat(manifest.get("generatedBy").asText()).isEqualTo(admin.email);
        assertThat(manifest.get("counts").get("request-traces.csv").asLong()).isPositive();
        assertThat(manifest.get("counts").get("audit-events.jsonl").asLong()).isPositive();

        // Et le téléchargement lui-même est un événement de sécurité.
        var downloaded = mongoClient.getDatabase("cabosse_control").getCollection("global_audit")
                .find(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("eventType", "LOGS_ARCHIVE_DOWNLOADED"),
                        com.mongodb.client.model.Filters.eq("tenantId", tenant.id)))
                .first();
        assertThat(downloaded).isNotNull();
        assertThat(downloaded.getString("category")).isEqualTo("SECURITY");
    }

    @Test
    void without_the_audit_right_the_archive_is_refused() {
        UserEntity admin = admin();
        UserEntity comptable = withRights(admin, "comptable", "ACCOUNTING_READ");

        givenAs(comptable)
                .queryParam("from", yesterday()).queryParam("to", tomorrow())
                .when().get("/api/v1/me/tenant/admin/logs/archive")
                .then().statusCode(403);

        // Le droit suffit, sans être administrateur.
        UserEntity controleur = withRights(admin, "controleur", "AUDIT_READ");
        givenAs(controleur)
                .queryParam("from", yesterday()).queryParam("to", tomorrow())
                .when().get("/api/v1/me/tenant/admin/logs/archive")
                .then().statusCode(200);
    }

    @Test
    void an_inverted_or_missing_period_is_a_bad_request() {
        UserEntity admin = admin();

        givenAs(admin)
                .queryParam("from", tomorrow()).queryParam("to", yesterday())
                .when().get("/api/v1/me/tenant/admin/logs/archive")
                .then().statusCode(400);

        givenAs(admin)
                .queryParam("from", yesterday())
                .when().get("/api/v1/me/tenant/admin/logs/archive")
                .then().statusCode(400);
    }

    @Test
    void the_platform_downloads_any_structure_and_the_structure_cannot_use_that_door() throws IOException {
        UserEntity admin = admin();
        twoWrites(admin);
        UserEntity platform = fixtures.createPlatformAdmin(
                "logs-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Agent", "Journaux");

        byte[] bytes = givenAs(platform)
                .queryParam("from", yesterday()).queryParam("to", tomorrow())
                .when().get("/api/v1/admin/tenants/" + tenant.id + "/logs/archive")
                .then().statusCode(200)
                .contentType("application/zip")
                .extract().asByteArray();
        Map<String, String> entries = unzip(bytes);
        assertThat(entries.get("request-traces.csv")).contains("/api/v1/articles");

        // L'accès inter-structure est tracé au journal.
        long crossTenant = mongoClient.getDatabase("cabosse_control").getCollection("global_audit")
                .countDocuments(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("eventType", "CROSS_TENANT_ACCESS"),
                        com.mongodb.client.model.Filters.eq("actorEmail", platform.email),
                        com.mongodb.client.model.Filters.eq("tenantId", tenant.id)));
        assertThat(crossTenant).isPositive();

        givenAs(admin)
                .queryParam("from", yesterday()).queryParam("to", tomorrow())
                .when().get("/api/v1/admin/tenants/" + tenant.id + "/logs/archive")
                .then().statusCode(403);
    }
}
