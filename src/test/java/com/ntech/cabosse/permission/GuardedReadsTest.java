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

/**
 * Les lectures qu'aucun droit ne gardait.
 *
 * <p>Un écran fermé ne ferme pas son adresse. Les reçus d'achat
 * producteur se lisaient sans aucun droit : qui connaissait l'adresse
 * lisait toute la collecte de la structure, montants compris (relevé le
 * 05/10/2026).</p>
 *
 * <p>Le journal d'audit souffrait de l'inverse : il portait son droit
 * mais restait derrière le rôle d'administrateur, si bien qu'on ne
 * pouvait pas l'ouvrir au contrôleur pour qui ce droit avait été
 * créé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class GuardedReadsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-garde-" + TestFixtures.randomSlugSuffix(), "Coopérative Gardes");
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

    @Test
    void la_collecte_ne_se_lit_pas_sans_le_droit_de_la_lire() {
        UserEntity a = admin();
        UserEntity etranger = withRights(a, "etranger", "STOCK_READ");

        for (String path : java.util.List.of(
                "/api/v1/producer-purchases",
                "/api/v1/producer-purchases/day-sheet")) {
            givenAs(etranger).when().get(path).then().statusCode(403);
        }

        UserEntity collecte = withRights(a, "collecte", "COLLECTION_READ");
        givenAs(collecte).when().get("/api/v1/producer-purchases").then().statusCode(200);
    }

    @Test
    void celui_qui_expedie_voit_les_recus_qu_il_expedie() {
        UserEntity a = admin();
        // Composer un bon d'acheminement suppose de voir les reçus qu'on
        // y met : le droit de mouvement de stock ouvre donc la liste.
        UserEntity magasinier = withRights(a, "magasinier", "STOCK_MOVE");
        givenAs(magasinier).when().get("/api/v1/producer-purchases").then().statusCode(200);
    }

    @Test
    void l_apercu_d_import_demande_le_meme_droit_que_l_application() {
        UserEntity a = admin();
        UserEntity lecteur = withRights(a, "lecteur", "COLLECTION_READ");

        // Vérifier un fichier, c'est en lire le contenu : l'aperçu ne
        // doit pas être la porte dérobée de l'import.
        givenAs(lecteur).contentType("application/json").body("[]")
                .when().post("/api/v1/producer-purchases/import/preview")
                .then().statusCode(403);
    }

    @Test
    void le_journal_d_audit_s_ouvre_sur_son_droit_sans_etre_administrateur() {
        UserEntity a = admin();
        UserEntity controleur = withRights(a, "controleur", "AUDIT_READ");

        // C'est tout l'objet d'un droit dédié : l'ouvrir à un contrôleur
        // ou à un expert-comptable sans lui donner les comptes.
        givenAs(controleur).when().get("/api/v1/me/tenant/admin/audit")
                .then().statusCode(200);

        UserEntity autre = withRights(a, "autre", "ACCOUNTING_READ");
        givenAs(autre).when().get("/api/v1/me/tenant/admin/audit")
                .then().statusCode(403);
    }

    @Test
    void l_inventaire_se_conduit_avec_le_droit_d_inventaire() {
        UserEntity a = admin();
        UserEntity operateur = withRights(a, "operateur", "STOCK_READ", "STOCK_MOVE");

        // Enregistrer des entrées et des sorties n'autorise pas à
        // régulariser l'existant.
        givenAs(operateur).contentType("application/json")
                .body("{\"siteId\":\"00000000-0000-0000-0000-000000000001\",\"lines\":[]}")
                .when().post("/api/v1/stocks/inventory")
                .then().statusCode(403);
    }
}
