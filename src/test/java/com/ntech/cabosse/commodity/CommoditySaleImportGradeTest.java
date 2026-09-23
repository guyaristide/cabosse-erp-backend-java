package com.ntech.cabosse.commodity;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Le grade d'un fichier de ventes en gros.
 *
 * <p>Un fichier portant « G1 » passait l'aperçu en « prête », puis
 * repartait écarté à la validation : le grade n'était pas au référentiel,
 * et seule la création s'en apercevait. L'utilisateur voyait une liste
 * vide, sans raison (relevé le 22/09/2026 sur les données de
 * SCOOPANAB).</p>
 *
 * <p>Deux règles en sont sorties. L'aperçu juge sur les mêmes contrôles
 * que l'écriture, sans quoi il ment. Et un grade simplement absent
 * s'ouvre, comme le sont déjà les fournisseurs et les articles d'un
 * import ; un grade <strong>désactivé</strong>, lui, reste un refus :
 * quelqu'un l'a retiré du jeu, et le réveiller en silence déferait sa
 * décision.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommoditySaleImportGradeTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-grd-" + TestFixtures.randomSlugSuffix(), "Structure Grades");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Grades";
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

    /** Le décor minimal d'une ligne acceptable : un client, un article vendable. */
    private void seed(UserEntity admin) {
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"ZAMACOM\", \"type\": \"COMPANY\" }")
                .when().post("/api/v1/customers").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "MERCHANDISE", "name": "Cacao", "unit": "kg",
                          "sellable": true, "purchasable": true }
                        """)
                .when().post("/api/v1/articles").then().statusCode(201);
    }

    /** Le site de départ, exigé par l'aperçu. */
    private String siteOf(UserEntity admin) {
        String existing = givenAs(admin).when().get("/api/v1/sites")
                .then().statusCode(200).extract().path("data[0].id");
        if (existing != null) return existing;
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    /** Une ligne minimale, dont on ne fait varier que le grade. */
    private String rowWithGrade(UserEntity admin, String grade) {
        return """
                [{ "rowNumber": 2, "customerName": "ZAMACOM", "productCode": "Cacao",
                   "date": "2026-09-22", "siteId": "%s",
                   "declaredKg": "1000", "acceptedKg": "990",
                   "montantFacture": "1000000", "grade": "%s" }]
                """.formatted(siteOf(admin), grade);
    }

    @Test
    void un_grade_absent_est_annonce_avant_la_validation() {
        UserEntity admin = admin();
        seed(admin);

        givenAs(admin).contentType("application/json").body(rowWithGrade(admin, "G1"))
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200)
                // La ligne n'est pas refusée pour si peu : elle est
                // prévenue. Le fichier partirait sinon en erreur pour un
                // grade qu'il suffisait d'ouvrir.
                .body("data.rows[0].notices", hasSize(1))
                .body("data.rows[0].notices[0].field", equalTo("grade"))
                .body("data.rows[0].notices[0].message", containsString("G1"));
    }

    @Test
    void un_grade_desactive_reste_un_refus() {
        UserEntity admin = admin();
        seed(admin);
        String id = givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"HG\", \"label\": \"Hors grade\", \"sortOrder\": 90 }")
                .when().post("/api/v1/quality-grades").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).queryParam("value", false)
                .when().patch("/api/v1/quality-grades/" + id + "/active")
                .then().statusCode(200);

        // Le retirer du jeu est une décision : un import ne la défait pas.
        givenAs(admin).contentType("application/json").body(rowWithGrade(admin, "HG"))
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200)
                .body("data.rows[0].status", equalTo("INVALID"))
                .body("data.rows[0].issues.field", org.hamcrest.Matchers.hasItem("grade"));
    }

    @Test
    void un_grade_deja_connu_ne_produit_aucun_avis() {
        UserEntity admin = admin();
        seed(admin);
        givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"GR1\", \"label\": \"Premier grade\", \"sortOrder\": 10 }")
                .when().post("/api/v1/quality-grades").then().statusCode(201);

        givenAs(admin).contentType("application/json").body(rowWithGrade(admin, "gr1"))
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200)
                // La casse ne fait pas un grade absent : « gr1 » est « GR1 ».
                .body("data.rows[0].notices", hasSize(0));
    }

    @Test
    void le_grade_annonce_est_reellement_cree_a_la_validation() {
        UserEntity admin = admin();
        seed(admin);

        // La vente elle-même peut encore échouer plus loin, faute de
        // stock : ce n'est pas l'objet ici. Ce qui compte est que le
        // grade promis par l'aperçu soit ouvert avant qu'on tente
        // d'écrire, sans quoi l'écriture le refuserait.
        givenAs(admin).contentType("application/json").body(rowWithGrade(admin, "G1"))
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200);

        givenAs(admin).when().get("/api/v1/quality-grades")
                .then().statusCode(200)
                .body("data.find { it.code == 'G1' }", notNullValue())
                .body("data.find { it.code == 'G1' }.active", equalTo(true));
    }

    @Test
    void un_fichier_entierement_en_erreur_ne_cree_aucun_grade() {
        UserEntity admin = admin();
        seed(admin);

        // Ligne sans site : elle ne sera jamais écrite. Ouvrir un grade
        // pour elle polluerait le référentiel au profit de rien.
        givenAs(admin).contentType("application/json")
                .body("""
                        [{ "rowNumber": 2, "customerName": "ZAMACOM", "productCode": "Cacao",
                           "date": "2026-09-22", "declaredKg": "1000", "acceptedKg": "990",
                           "grade": "ZZ9" }]
                        """)
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(0));

        givenAs(admin).when().get("/api/v1/quality-grades")
                .then().statusCode(200)
                .body("data.find { it.code == 'ZZ9' }", org.hamcrest.Matchers.nullValue());
    }

}
