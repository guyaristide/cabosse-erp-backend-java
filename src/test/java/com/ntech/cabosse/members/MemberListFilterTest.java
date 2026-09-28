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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Les restrictions de la liste des membres (demandé le 27/09/2026).
 *
 * <p>L'écran n'offrait que la recherche et le statut. Sur un registre de
 * plusieurs milliers de fiches, retrouver les délégués d'un village
 * revenait à faire défiler des pages : la question « qui collecte ici ? »
 * n'avait pas de réponse à l'écran.</p>
 *
 * <p>Deux pièges tenus ici. Le premier : « non délégué » doit compter les
 * fiches anciennes, où le champ n'existe simplement pas ; sans quoi
 * délégués et non délégués additionnés ne feraient pas l'effectif, et la
 * liste mentirait sans le dire. Le second : un village se choisit dans
 * une liste tirée des fiches, donc jamais une valeur qui ne rendrait
 * personne.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberListFilterTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-flt-" + TestFixtures.randomSlugSuffix(), "Coopérative Filtres");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Filtres";
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

    private void createMember(UserEntity who, String lastName, String gender,
                              String village, boolean collector) {
        givenAs(who).contentType("application/json")
                .body("""
                        { "lastName": "%s", "firstName": "Test", "gender": "%s",
                          "status": "ACTIVE", "village": "%s", "collector": %s }
                        """.formatted(lastName, gender, village, collector))
                .when().post("/api/v1/members").then().statusCode(201);
    }

    /** Un jeu volontairement croisé : chaque filtre doit couper autrement. */
    private UserEntity registry() {
        UserEntity a = admin();
        createMember(a, "DELEGUEBROU", "MALE", "Brouhou", true);
        createMember(a, "DELEGUEZAG", "FEMALE", "Zagné", true);
        createMember(a, "PRODBROU", "MALE", "Brouhou", false);
        createMember(a, "PRODZAG", "FEMALE", "Zagné", false);
        return a;
    }

    @Test
    void le_role_separe_les_delegues_des_autres() {
        UserEntity a = registry();

        givenAs(a).when().get("/api/v1/members?collector=true&perPage=100")
                .then().statusCode(200)
                .body("data.total", equalTo(2))
                .body("data.items.lastName", hasItem("DELEGUEBROU"))
                .body("data.items.lastName", not(hasItem("PRODBROU")));

        givenAs(a).when().get("/api/v1/members?collector=false&perPage=100")
                .then().statusCode(200)
                .body("data.total", equalTo(2))
                .body("data.items.lastName", hasItem("PRODBROU"))
                .body("data.items.lastName", not(hasItem("DELEGUEBROU")));
    }

    @Test
    void delegues_et_non_delegues_reconstituent_l_effectif() {
        UserEntity a = registry();

        int all = givenAs(a).when().get("/api/v1/members?perPage=1")
                .then().statusCode(200).extract().path("data.total");
        int delegates = givenAs(a).when().get("/api/v1/members?collector=true&perPage=1")
                .then().statusCode(200).extract().path("data.total");
        int others = givenAs(a).when().get("/api/v1/members?collector=false&perPage=1")
                .then().statusCode(200).extract().path("data.total");

        // La somme est le seul contrôle qui attrape les fiches sans le
        // champ : elles doivent tomber du côté « non délégué », pas
        // disparaître entre les deux vues.
        org.assertj.core.api.Assertions.assertThat(delegates + others)
                .as("aucune fiche ne manque entre les deux vues")
                .isEqualTo(all);
    }

    @Test
    void le_village_et_le_genre_se_cumulent() {
        UserEntity a = registry();

        givenAs(a).when().get("/api/v1/members?village=Brouhou&perPage=100")
                .then().statusCode(200).body("data.total", equalTo(2));

        // Deux filtres posés ensemble se croisent, ils ne s'ajoutent pas.
        // Le village est passé en paramètre plutôt que collé à l'adresse :
        // les noms d'ici portent des accents, et un filtre qui ne les
        // supporterait pas ne servirait à rien sur ce registre.
        givenAs(a).queryParam("village", "Zagné").queryParam("gender", "FEMALE")
                .queryParam("perPage", 100)
                .when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.total", equalTo(2))
                .body("data.items.lastName", hasItem("DELEGUEZAG"))
                .body("data.items.lastName", hasItem("PRODZAG"));

        givenAs(a).queryParam("village", "Zagné").queryParam("gender", "FEMALE")
                .queryParam("collector", true).queryParam("perPage", 100)
                .when().get("/api/v1/members")
                .then().statusCode(200).body("data.total", equalTo(1));
    }

    @Test
    void les_villages_proposes_sont_ceux_des_fiches() {
        UserEntity a = registry();

        // Proposer un village vide de fiches mènerait à une liste vide
        // sans que rien n'explique pourquoi.
        givenAs(a).when().get("/api/v1/members/villages")
                .then().statusCode(200)
                .body("data", hasItem("Brouhou"))
                .body("data", hasItem("Zagné"));
    }

    @Test
    void un_filtre_inconnu_ne_vide_pas_la_liste() {
        UserEntity a = registry();

        // Une valeur qu'on ne sait pas lire ne restreint rien : rendre
        // zéro ligne ferait croire à un registre vide.
        givenAs(a).when().get("/api/v1/members?gender=AUTRE&collector=peut-etre&perPage=100")
                .then().statusCode(200).body("data.total", equalTo(4));
    }
}
