package com.ntech.cabosse.members;

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

import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Réimporter le même fichier ne double pas les parcelles
 * (relevé le 30/09/2026).
 *
 * <p>Le rapprochement ne se faisait que par code plantation. La plupart
 * des registres n'en ont pas : chaque réimport recréait donc les
 * parcelles, et un producteur passait de une à deux puis à trois, avec
 * sa superficie multipliée d'autant.</p>
 *
 * <p>Le nom est dérivé du producteur et du rang de sa parcelle : il
 * désigne la même parcelle à chaque passage, et sert de rattrapage quand
 * le code manque.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportReimportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-reimp-" + TestFixtures.randomSlugSuffix(), "Coopérative Réimport");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Les parcelles relèvent de la production agricole : sans cette
        // activité, elles ne sont pas de ce tenant.
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Réimport";
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

    /** Le fichier réel : un producteur, une parcelle, aucun code plantation. */
    private void importFile(UserEntity a, String surface) {
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "code": "CRA-A-001", "lastName": "TIEMOKO",
                            "firstName": "Sylvain", "gender": "MALE",
                            "parcelSurfaceHa": "%s", "parcelCrop": "Cacao" } ]
                        """.formatted(surface))
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200);
    }

    private int parcelCount(UserEntity a) {
        return givenAs(a).queryParam("perPage", 100).when().get("/api/v1/parcels")
                .then().statusCode(200).extract().path("data.total");
    }

    @Test
    void le_meme_fichier_passe_deux_fois_ne_cree_qu_une_parcelle() {
        UserEntity a = admin();

        importFile(a, "2.5");
        int afterFirst = parcelCount(a);
        importFile(a, "2.5");

        // Sans rattrapage, le producteur se retrouvait avec deux parcelles
        // et une superficie doublée : la traçabilité et le devoir de
        // vigilance s'appuient pourtant dessus.
        org.assertj.core.api.Assertions.assertThat(parcelCount(a))
                .as("le réimport met à jour, il ne recrée pas")
                .isEqualTo(afterFirst);
    }

    @Test
    void le_reimport_met_la_parcelle_a_jour() {
        UserEntity a = admin();

        importFile(a, "2.5");
        // Le fichier corrigé porte la bonne superficie : c'est la raison
        // même d'un réimport, et elle doit arriver sur la parcelle.
        importFile(a, "3.75");

        givenAs(a).queryParam("perPage", 100).when().get("/api/v1/parcels")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].surfaceHa", equalTo(3.75f));
    }
}
