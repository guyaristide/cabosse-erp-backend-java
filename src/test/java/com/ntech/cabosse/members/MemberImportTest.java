package com.ntech.cabosse.members;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.imports.FuzzyLabels;
import com.ntech.cabosse.iddocument.service.IdDocumentTypeCanonical;
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

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Import de membres. Trois comportements font la valeur de l'écran : le
 * rapprochement d'un producteur déjà enregistré, la normalisation des
 * libellés saisis à la main, et le sort réservé aux ménages incohérents.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-import-" + TestFixtures.randomSlugSuffix(), "Coopérative Import");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
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
    void common_document_labels_collapse_onto_one_canonical_type() {
        // Fautes de frappe et abréviations désignent la même pièce.
        assertThat(IdDocumentTypeCanonical.resolve("CNI"))
                .isEqualTo("Carte nationale d'identité");
        assertThat(IdDocumentTypeCanonical.resolve("carte nationnale d'identite"))
                .isEqualTo("Carte nationale d'identité");
        assertThat(IdDocumentTypeCanonical.resolve("C.N.I."))
                .isEqualTo("Carte nationale d'identité");
        assertThat(IdDocumentTypeCanonical.resolve("Passport"))
                .isEqualTo("Passeport");
        // Un type inconnu n'est pas écrasé : on garde ce que l'agent a saisi.
        assertThat(IdDocumentTypeCanonical.resolve("Carte de réfugié"))
                .isEqualTo("Carte de réfugié");
        // Deux notions distinctes ne se confondent pas.
        assertThat(FuzzyLabels.matches("Passeport", "Permis de conduire")).isFalse();
    }

    @Test
    void preview_flags_an_incoherent_household_without_blocking_the_file() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        [
                          { "rowNumber": 1, "lastName": "N'Guessan", "firstName": "Konan",
                            "gender": "Homme", "phone": "0551161559",
                            "childrenCount": "7", "girlsCount": "1", "boysCount": "3" },
                          { "rowNumber": 2, "lastName": "Doumbia", "firstName": "Seydou",
                            "gender": "Homme", "phone": "0707080910" }
                        ]
                        """)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.totalRows", equalTo(2))
                .body("data.readyRows", equalTo(1))
                .body("data.warningRows", equalTo(1))
                .body("data.rows[0].status", equalTo("WARNING"))
                .body("data.rows[0].issues", hasSize(1))
                .body("data.rows[1].status", equalTo("READY"));
    }

    @Test
    void commit_creates_then_matches_the_same_producer_on_a_second_pass() {
        UserEntity admin = tenantAdmin();
        String body = """
                [
                  { "rowNumber": 1, "externalCodeType": "Code producteur",
                    "externalCode": "CCC-2021-183667",
                    "lastName": "N'Guessan", "firstName": "Konan", "gender": "Homme",
                    "idDocType": "cni", "idDocNumber": "CI60013389083",
                    "phone": "0551161559", "village": "Méagui", "section": "Section Méagui" }
                ]
                """;

        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.updatedCount", equalTo(0))
                // Section et type de pièce absents sont créés à la volée.
                .body("data.createdSections", hasSize(1))
                .body("data.createdIdDocTypes", hasSize(1))
                .body("data.createdIdDocTypes[0]", equalTo("Carte nationale d'identité"));

        // Deuxième passe du même fichier : mise à jour, jamais de doublon.
        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.updatedCount", equalTo(1))
                // Les référentiels ne se dédoublent pas non plus.
                .body("data.createdSections", hasSize(0))
                .body("data.createdIdDocTypes", hasSize(0));

        givenAs(admin)
                .when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.total", equalTo(1));
    }

    @Test
    void warning_rows_are_applied_only_when_explicitly_accepted() {
        UserEntity admin = tenantAdmin();
        String body = """
                [
                  { "rowNumber": 1, "lastName": "Traoré", "firstName": "Salif", "gender": "Homme",
                    "phone": "0500000001",
                    "childrenCount": "7", "girlsCount": "1", "boysCount": "3" }
                ]
                """;

        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedCount", equalTo(1));

        // Forcé : le producteur entre, mais sans les compteurs faux.
        String memberId = givenAs(admin).contentType("application/json").body(body)
                .queryParam("includeWarnings", true)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.householdsSkipped", equalTo(1))
                .extract().path("data.createdIds[0]");

        givenAs(admin)
                .when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.name", equalTo("Traoré Salif"))
                .body("data.household.childrenCount", org.hamcrest.Matchers.nullValue());
    }
}
