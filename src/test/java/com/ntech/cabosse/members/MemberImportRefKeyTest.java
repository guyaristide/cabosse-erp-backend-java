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
import static org.hamcrest.Matchers.containsString;

/**
 * Un numéro de pièce déjà pris se voit à la vérification
 * (relevé le 30/09/2026).
 *
 * <p>L'aperçu annonçait 4131 lignes prêtes et zéro erreur ; trente-quatre
 * sont tombées à l'écriture, toutes sur le même motif : un numéro déjà
 * porté par un autre producteur. Le contrôle existait bien, mais
 * seulement au moment d'écrire, une fois le fichier jugé bon.</p>
 *
 * <p>Annoncer prêt ce qui sera refusé est pire que de refuser : on
 * valide en confiance, et l'écart ne se découvre qu'après coup.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportRefKeyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-refkey-" + TestFixtures.randomSlugSuffix(), "Coopérative Numéros");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Numéros";
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

    /**
     * Déclare le type de pièce qui sert de clé.
     *
     * <p>Un numéro ne désigne un producteur que si son type est marqué
     * comme tel dans le référentiel : sans cette déclaration, aucune
     * collision n'existe et la règle ne s'applique pas.</p>
     */
    private void declareNationalIdAsKey(UserEntity a) {
        givenAs(a).contentType("application/json")
                .body("""
                        { "code": "nni", "name": "Identifiant national",
                          "identityProof": true, "usableAsProducerRef": true }
                        """)
                .when().post("/api/v1/id-document-types").then().statusCode(201);
    }

    private io.restassured.response.ValidatableResponse preview(UserEntity a, String rows) {
        return givenAs(a).contentType("application/json").body(rows)
                .when().post("/api/v1/members/import/preview").then().statusCode(200);
    }

    @Test
    void deux_lignes_du_meme_fichier_ne_partagent_pas_un_numero() {
        UserEntity a = admin();

        // Le cas exact des 34 lignes : le contrôle ne regardait ni le
        // fichier ni la base, et l'aperçu les annonçait prêtes.
        preview(a, """
                [ { "rowNumber": 1, "lastName": "GONLY", "firstName": "Gervais",
                    "gender": "MALE", "nationalIdNumber": "10500800016" },
                  { "rowNumber": 2, "lastName": "SEU", "firstName": "Brise",
                    "gender": "MALE", "nationalIdNumber": "10500800016" } ]
                """)
                .body("data.invalidRows", equalTo(1))
                .body("data.rows.find { it.rowNumber == 2 }.issues[0].message",
                        containsString("10500800016"));
    }

    @Test
    void un_numero_deja_en_base_est_signale_avant_d_ecrire() {
        UserEntity a = admin();
        declareNationalIdAsKey(a);
        // Créé par le même chemin que les autres : c'est l'import qui
        // pose le numéro comme clé, et c'est ce cas-là qui a échoué.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "lastName": "GONLY", "firstName": "Gervais",
                            "gender": "MALE", "nationalIdNumber": "10500800016" } ]
                        """)
                .when().post("/api/v1/members/import/commit").then().statusCode(200);

        preview(a, """
                [ { "rowNumber": 1, "lastName": "SEU", "firstName": "Brise",
                    "gender": "MALE", "nationalIdNumber": "10500800016" } ]
                """)
                .body("data.invalidRows", equalTo(1))
                .body("data.rows[0].issues[0].message", containsString("GONLY"));
    }

    @Test
    void un_fichier_sans_collision_reste_pret() {
        UserEntity a = admin();

        // Le contrôle ne doit pas refuser ce qui va bien : deux numéros
        // différents passent, et une ligne sans numéro aussi.
        preview(a, """
                [ { "rowNumber": 1, "lastName": "GONLY", "firstName": "Gervais",
                    "gender": "MALE", "nationalIdNumber": "10500800016" },
                  { "rowNumber": 2, "lastName": "SEU", "firstName": "Brise",
                    "gender": "MALE", "nationalIdNumber": "10500800017" },
                  { "rowNumber": 3, "lastName": "KOUAME", "firstName": "Awa",
                    "gender": "FEMALE" } ]
                """)
                .body("data.invalidRows", equalTo(0))
                .body("data.readyRows", equalTo(3));
    }
}
