package com.ntech.cabosse.shared.export;

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

import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un export part réellement dans la langue demandée.
 *
 * <p>294 en-têtes de colonnes sont passés du littéral au catalogue. Les
 * contrôles statiques disent que les clés existent et que le français n'a
 * pas bougé ; ils ne disent pas que la chaîne complète, du filtre de
 * langue jusqu'à l'octet écrit dans le fichier, aboutit. C'est ce que
 * vérifie ce test, en lisant le CSV produit.</p>
 *
 * <p>Il couvre au passage le piège qui a réellement mordu : une
 * apostrophe non doublée dans un message paramétré fait disparaître à la
 * fois l'apostrophe et la valeur, sans lever la moindre erreur.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ExportLocaleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-export-" + TestFixtures.randomSlugSuffix(), "Coopérative export");
        // Sans activité agricole, les capacités parcelles et récoltes ne sont
        // pas actives et l'accès est refusé avant même d'atteindre l'export.
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
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

    private String csv(UserEntity user, String path, String language) {
        var request = givenAs(user);
        if (language != null) request = request.header("Accept-Language", language);
        return request.when().get(path).then().statusCode(200).extract().asString();
    }

    @Test
    void the_parcel_export_headers_follow_the_request_language() {
        UserEntity admin = tenantAdmin();

        String french = csv(admin, "/api/v1/parcels/export?format=csv", null);
        assertThat(french)
                .as("sans en-tête, la référence reste le français")
                .contains("Code plantation")
                .contains("Superficie (ha)");

        String english = csv(admin, "/api/v1/parcels/export?format=csv", "en");
        assertThat(english)
                .as("en anglais, les mêmes colonnes traduites")
                .contains("Plantation code")
                .doesNotContain("Code plantation");
    }

    @Test
    void an_unserved_language_falls_back_to_french_never_to_a_raw_key() {
        UserEntity admin = tenantAdmin();
        String spanish = csv(admin, "/api/v1/parcels/export?format=csv", "es");

        assertThat(spanish).contains("Code plantation");
        // Une clé absente se renvoie elle-même : elle sortirait telle quelle
        // dans le fichier, ce qui est invisible en revue mais pas à l'usage.
        assertThat(spanish).doesNotContain("m.imp-h-");
    }

    @Test
    void no_export_header_ever_leaks_a_catalog_key() {
        UserEntity admin = tenantAdmin();
        for (String language : new String[] {null, "en"}) {
            for (String path : new String[] {
                    "/api/v1/parcels/export?format=csv",
                    "/api/v1/harvests/export?format=csv"}) {
                assertThat(csv(admin, path, language))
                        .as("%s en %s", path, language == null ? "fr" : language)
                        .doesNotContain("m.imp-h-")
                        .doesNotContain("{0}");
            }
        }
    }
}
