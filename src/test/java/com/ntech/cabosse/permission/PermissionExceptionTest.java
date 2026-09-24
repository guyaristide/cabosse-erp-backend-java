package com.ntech.cabosse.permission;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Droits accordés ou retirés à une personne seule (backlog ADM-03).
 *
 * <p>Un profil se partage. Le compléter pour quelqu'un le donne à tous
 * ceux qui le portent, et l'amputer les prive tous : restait à fabriquer
 * un profil sur mesure par cas particulier, jusqu'à ce que le référentiel
 * des profils compte autant d'entrées que la structure a de personnes.</p>
 *
 * <p>Ce que ces tests tiennent, au delà du fonctionnement : les quatre
 * refus. Un code inconnu ne s'écrit pas en silence, un droit hors des
 * capacités de la structure ne s'accorde pas (il ne donnerait accès à
 * rien), un administrateur n'en reçoit aucune, et personne ne se retire
 * à soi-même la gestion des utilisateurs.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PermissionExceptionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-exc-" + TestFixtures.randomSlugSuffix(), "Coopérative Exceptions");
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

    private String createRole(UserEntity admin, String name, String... permissions) {
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(name, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
    }

    private void assign(UserEntity admin, UserEntity target, String roleId) {
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + target.id)
                .then().statusCode(204);
    }

    /** Les exceptions d'une personne, remplacées en bloc. */
    private io.restassured.response.ValidatableResponse setExceptions(
            UserEntity admin, UserEntity target, String body) {
        return givenAs(admin).contentType("application/json")
                .body("{ \"exceptions\": [%s] }".formatted(body))
                .when().put("/api/v1/me/tenant/admin/users/" + target.id + "/permission-exceptions")
                .then();
    }

    /** Ce que le serveur reconnaît à cette personne, à cet instant. */
    private io.restassured.response.ValidatableResponse rightsOf(UserEntity who) {
        return givenAs(who).when().get("/api/v1/me").then().statusCode(200);
    }

    @Test
    void un_droit_accorde_a_une_personne_ne_touche_pas_son_profil() {
        UserEntity a = admin();
        UserEntity magasinier = user(Roles.USER, "magasinier");
        UserEntity collegue = user(Roles.USER, "collegue");
        String role = createRole(a, "Magasinier " + TestFixtures.randomSlugSuffix(), "STOCK_READ");
        assign(a, magasinier, role);
        assign(a, collegue, role);

        setExceptions(a, magasinier,
                "{ \"code\": \"REFERENTIAL_READ\", \"mode\": \"GRANT\","
                        + " \"reason\": \"Remplace le gestionnaire pendant son congé\" }")
                .statusCode(200);

        rightsOf(magasinier).body("data.permissions", hasItem("REFERENTIAL_READ"));
        // Le profil n'a pas bougé : c'est tout l'objet de l'exception.
        rightsOf(collegue).body("data.permissions", not(hasItem("REFERENTIAL_READ")));
    }

    @Test
    void un_droit_retire_a_une_personne_laisse_le_profil_aux_autres() {
        UserEntity a = admin();
        UserEntity magasinier = user(Roles.USER, "magasinier");
        UserEntity collegue = user(Roles.USER, "collegue");
        String role = createRole(a, "Magasinier " + TestFixtures.randomSlugSuffix(),
                "STOCK_READ", "STOCK_MOVE");
        assign(a, magasinier, role);
        assign(a, collegue, role);

        setExceptions(a, magasinier,
                "{ \"code\": \"STOCK_MOVE\", \"mode\": \"REVOKE\","
                        + " \"reason\": \"Ne fait plus les sorties\" }")
                .statusCode(200);

        rightsOf(magasinier)
                .body("data.permissions", hasItem("STOCK_READ"))
                .body("data.permissions", not(hasItem("STOCK_MOVE")));
        rightsOf(collegue).body("data.permissions", hasItem("STOCK_MOVE"));
    }

    @Test
    void l_exception_dit_qui_l_a_posee_et_pourquoi() {
        UserEntity a = admin();
        UserEntity cible = user(Roles.USER, "cible");

        setExceptions(a, cible,
                "{ \"code\": \"REFERENTIAL_READ\", \"mode\": \"GRANT\","
                        + " \"reason\": \"Remplacement de Koffi\" }")
                .statusCode(200)
                // Sans le motif, une exception devient au bout de quelques
                // mois un droit dont plus personne ne sait s'il tient.
                .body("data.permissionExceptions[0].reason", equalTo("Remplacement de Koffi"))
                .body("data.permissionExceptions[0].grantedByEmail", equalTo(a.email))
                .body("data.permissionExceptions[0].grantedAt", notNullValue());
    }

    @Test
    void la_liste_envoyee_remplace_la_precedente() {
        UserEntity a = admin();
        UserEntity cible = user(Roles.USER, "cible");

        setExceptions(a, cible,
                "{ \"code\": \"REFERENTIAL_READ\", \"mode\": \"GRANT\" }").statusCode(200);
        // Une exception absente du corps disparaît : deux écrans ouverts
        // en même temps se contrediraient si l'on ajoutait au lieu de
        // remplacer.
        setExceptions(a, cible,
                "{ \"code\": \"STOCK_READ\", \"mode\": \"GRANT\" }")
                .statusCode(200)
                .body("data.permissionExceptions", hasSize(1))
                .body("data.permissionExceptions[0].code", equalTo("STOCK_READ"));

        rightsOf(cible).body("data.permissions", not(hasItem("REFERENTIAL_READ")));
    }

    @Test
    void un_code_inconnu_est_refuse_plutot_qu_ignore() {
        UserEntity a = admin();
        UserEntity cible = user(Roles.USER, "cible");

        // Écrit en silence, il ne ferait rien et personne ne saurait
        // pourquoi le droit n'arrive pas.
        setExceptions(a, cible, "{ \"code\": \"DROIT_IMAGINAIRE\", \"mode\": \"GRANT\" }")
                .statusCode(422)
                .body("statusMessage", containsString("DROIT_IMAGINAIRE"));
    }

    @Test
    void un_droit_hors_des_capacites_de_la_structure_est_refuse() {
        UserEntity a = admin();
        UserEntity cible = user(Roles.USER, "cible");

        // Sans filière déclarée, le séchage n'existe pas pour cette
        // structure : accorder le droit promettrait un accès à un écran
        // qu'elle n'a pas.
        setExceptions(a, cible, "{ \"code\": \"DRYING_WRITE\", \"mode\": \"GRANT\" }")
                .statusCode(422);
    }

    @Test
    void un_administrateur_ne_recoit_aucune_exception() {
        UserEntity a = admin();
        UserEntity autreAdmin = user(Roles.TENANT_ADMIN, "admin2");

        // Il détient déjà tout : un ajout n'apporterait rien, et un
        // retrait enfermerait la structure hors de son administration.
        setExceptions(a, autreAdmin, "{ \"code\": \"STOCK_MOVE\", \"mode\": \"REVOKE\" }")
                .statusCode(422);
        rightsOf(autreAdmin).body("data.permissions", hasItem("STOCK_MOVE"));
    }

    @Test
    void le_meme_droit_accorde_et_retire_est_refuse() {
        UserEntity a = admin();
        UserEntity cible = user(Roles.USER, "cible");

        // L'ordre de la liste trancherait au hasard.
        setExceptions(a, cible,
                "{ \"code\": \"STOCK_READ\", \"mode\": \"GRANT\" },"
                        + "{ \"code\": \"STOCK_READ\", \"mode\": \"REVOKE\" }")
                .statusCode(422);
    }

    @Test
    void personne_ne_se_retire_a_soi_meme_la_gestion_des_utilisateurs() {
        UserEntity a = admin();
        UserEntity gestionnaire = user(Roles.USER, "gestionnaire");
        String role = createRole(a, "Gestionnaire " + TestFixtures.randomSlugSuffix(),
                "USER_MANAGE");
        assign(a, gestionnaire, role);

        // Personne ne pourrait le lui rendre, sauf à passer par un
        // administrateur qui n'existe peut-être plus.
        setExceptions(gestionnaire, gestionnaire,
                "{ \"code\": \"USER_MANAGE\", \"mode\": \"REVOKE\" }")
                .statusCode(422);
    }

    @Test
    void poser_une_exception_demande_le_droit_de_gerer_les_utilisateurs() {
        UserEntity a = admin();
        UserEntity simple = user(Roles.USER, "simple");
        UserEntity cible = user(Roles.USER, "cible");
        String role = createRole(a, "Lecteur " + TestFixtures.randomSlugSuffix(), "STOCK_READ");
        assign(a, simple, role);

        setExceptions(simple, cible, "{ \"code\": \"STOCK_READ\", \"mode\": \"GRANT\" }")
                .statusCode(403);
    }
}
