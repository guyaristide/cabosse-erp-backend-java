package com.ntech.cabosse.permission;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.permission.entity.Permission;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Un droit par référentiel, les deux globaux conservés (24/09/2026).
 *
 * <p>Vingt-deux référentiels vivaient sous une seule paire de droits :
 * confier les fournisseurs à quelqu'un lui ouvrait aussi les sites, le
 * plan comptable analytique et les barèmes de campagne. Chaque
 * référentiel a maintenant sa lecture et son écriture.</p>
 *
 * <p>Ce que ces tests tiennent, et qui compte plus que le découpage :
 * les deux droits globaux ouvrent toujours la liste entière. Sans cette
 * expansion, tous les profils existants perdraient l'accès d'un coup,
 * puisqu'ils portent le droit global et que les écrans exigent désormais
 * le droit précis. Et l'expansion vient avant les exceptions, pour qu'on
 * puisse détenir l'écriture globale et se voir retirer un seul
 * référentiel.</p>
 *
 * <p>Ils tiennent aussi ce que le découpage ne doit pas casser : les
 * lectures qu'une autre fonction autorisait déjà. Le magasinier lit les
 * clients par son droit de stock depuis le 20/09, et cela doit rester
 * vrai.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ReferentialGranularityTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-gran-" + TestFixtures.randomSlugSuffix(), "Coopérative Granularité");
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
    void chaque_referentiel_a_sa_lecture_et_son_ecriture() {
        // Dérivés du nom : une liste tenue à la main oublierait le droit
        // qu'on vient d'ajouter, et l'oubli ne se verrait qu'au moment où
        // quelqu'un perd un accès.
        assertThat(Permission.referentialReads()).hasSameSizeAs(Permission.referentialWrites());
        assertThat(Permission.referentialReads()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(Permission.referentialReads()).contains(Permission.REF_SUPPLIER_READ);
        assertThat(Permission.referentialWrites()).contains(Permission.REF_SITE_WRITE);
        // Les deux globaux ne font pas partie de la liste qu'ils ouvrent.
        assertThat(Permission.referentialReads()).doesNotContain(Permission.REFERENTIAL_READ);
    }

    @Test
    void le_droit_global_de_lecture_ouvre_toujours_tous_les_referentiels() {
        UserEntity a = admin();
        UserEntity lecteur = withRights(a, "lecteur", "REFERENTIAL_READ");

        // Sans l'expansion, tous les profils existants perdraient leur
        // accès d'un coup : ils portent le droit global, et les écrans
        // exigent désormais le droit précis.
        givenAs(lecteur).when().get("/api/v1/suppliers").then().statusCode(200);
        givenAs(lecteur).when().get("/api/v1/articles").then().statusCode(200);
        givenAs(lecteur).when().get("/api/v1/units").then().statusCode(200);
        givenAs(lecteur).when().get("/api/v1/quality-grades").then().statusCode(200);
    }

    @Test
    void le_droit_global_d_ecriture_ouvre_toujours_tous_les_referentiels() {
        UserEntity a = admin();
        UserEntity gestionnaire = withRights(a, "gest", "REFERENTIAL_READ", "REFERENTIAL_WRITE");

        givenAs(gestionnaire).contentType("application/json")
                .body("{ \"name\": \"Fournisseur Test\" }")
                .when().post("/api/v1/suppliers").then().statusCode(201);
        givenAs(gestionnaire).contentType("application/json")
                .body("{ \"code\": \"G9\", \"label\": \"Grade neuf\", \"sortOrder\": 99 }")
                .when().post("/api/v1/quality-grades").then().statusCode(201);
    }

    @Test
    void un_droit_precis_n_ouvre_que_son_referentiel() {
        UserEntity a = admin();
        UserEntity tiers = withRights(a, "tiers", "REF_SUPPLIER_READ", "REF_SUPPLIER_WRITE");

        givenAs(tiers).when().get("/api/v1/suppliers").then().statusCode(200);
        givenAs(tiers).contentType("application/json")
                .body("{ \"name\": \"Fournisseur Confié\" }")
                .when().post("/api/v1/suppliers").then().statusCode(201);

        // C'est tout l'objet du découpage : les vingt et un autres
        // référentiels restent fermés.
        givenAs(tiers).when().get("/api/v1/units").then().statusCode(403);
        givenAs(tiers).contentType("application/json")
                .body("{ \"code\": \"Z9\", \"label\": \"Grade\", \"sortOrder\": 99 }")
                .when().post("/api/v1/quality-grades").then().statusCode(403);
    }

    @Test
    void une_exception_retire_un_seul_referentiel_a_qui_detient_le_global() {
        UserEntity a = admin();
        UserEntity gestionnaire = withRights(a, "gest", "REFERENTIAL_READ", "REFERENTIAL_WRITE");

        givenAs(a).contentType("application/json")
                .body("{ \"exceptions\": [{ \"code\": \"REF_SITE_WRITE\", \"mode\": \"REVOKE\","
                        + " \"reason\": \"Ne touche pas au plan de sites\" }] }")
                .when().put("/api/v1/me/tenant/admin/users/" + gestionnaire.id
                        + "/permission-exceptions")
                .then().statusCode(200);

        // L'expansion vient avant les exceptions : l'inverse rendrait ce
        // retrait sans effet.
        givenAs(gestionnaire).when().get("/api/v1/me").then().statusCode(200)
                .body("data.permissions", not(hasItem("REF_SITE_WRITE")))
                .body("data.permissions", hasItem("REF_SUPPLIER_WRITE"));
    }

    @Test
    void une_ecriture_de_referentiel_ouverte_au_role_reste_gardee_par_une_permission() throws Exception {
        // Le rôle a été ouvert le 24/09/2026 pour que la permission serve
        // enfin : jusque-là, REFERENTIAL_WRITE donné à un profil non
        // administrateur ne faisait rien, le rôle refusant avant lui.
        // Une écriture dont la garde de permission disparaîtrait
        // deviendrait alors ouverte à toute personne connectée.
        java.util.List<String> nus = new java.util.ArrayList<>();
        try (var paths = java.nio.file.Files.walk(
                java.nio.file.Path.of("src/main/java/com/ntech/cabosse"))) {
            for (java.nio.file.Path file : paths
                    .filter(f -> f.toString().endsWith("Resource.java")).toList()) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(file);
                String source = String.join("\n", lines);
                if (!source.contains("Permission.REF_")) continue;
                String classGuard = null;
                for (String l : lines) {
                    if (!l.startsWith(" ") && l.contains("@RequiresPermission(Permission.")) {
                        classGuard = l;
                    }
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains("Roles.USER")) continue;
                    String guard = classGuard;
                    String verb = null;
                    for (int j = Math.max(0, i - 12); j < Math.min(lines.size(), i + 4); j++) {
                        String l = lines.get(j);
                        if (l.contains("@RequiresPermission(Permission.")) guard = l;
                        if (l.contains("@POST") || l.contains("@PUT")
                                || l.contains("@PATCH") || l.contains("@DELETE")) {
                            verb = l.trim();
                        }
                    }
                    if (verb != null && (guard == null || !guard.contains("_WRITE"))) {
                        nus.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(nus)
                .as("écritures de référentiel ouvertes au rôle sans garde d'écriture")
                .isEmpty();
    }

    @Test
    void les_lectures_deja_autorisees_par_une_autre_fonction_survivent() {
        UserEntity a = admin();
        UserEntity magasinier = withRights(a, "magasinier", "STOCK_READ");

        // Le magasinier lit les clients par son droit de stock depuis le
        // 20/09 : le découpage ne doit pas le lui reprendre, faute de
        // quoi le bordereau de sortie retrouve son sélecteur vide.
        givenAs(magasinier).when().get("/api/v1/customers").then().statusCode(200);
    }

    @Test
    void tout_droit_de_referentiel_a_un_libelle_dans_les_deux_langues() {
        UserEntity a = admin();

        // Un droit sans libellé s'affiche en code brut dans la liste à
        // cocher : « REF_QUALITY_NORM_WRITE » ne dit rien à personne.
        for (Permission p : Permission.referentialReads()) {
            assertThat(p.label()).as("libellé de %s", p).doesNotContain("m.per-");
        }
        givenAs(a).when().get("/api/v1/tenant-roles/permissions")
                .then().statusCode(200)
                .body("data.code", hasItem("REF_SUPPLIER_READ"))
                .body("data.code", hasItem("REF_SUPPLIER_WRITE"))
                // Les deux globaux restent proposables.
                .body("data.code", hasItem("REFERENTIAL_READ"))
                .body("data.code", hasItem("REFERENTIAL_WRITE"));
    }
}
