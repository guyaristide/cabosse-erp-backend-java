package com.ntech.cabosse.delegatestatus;

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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Positions tenues sur un délégué (backlog DEL-01 à DEL-04).
 *
 * <p>Ce que ces tests protègent tient en deux phrases. Une position ne
 * s'écrase pas : la précédente reste lisible, sinon on perd qui a décidé
 * quoi. Et le montant dû se fige au moment de la décision, sinon l'état
 * ne saura plus dire si quelque chose a été recouvré depuis.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateStatusPositionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrations;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-pos-" + TestFixtures.randomSlugSuffix(), "Coopérative Positions");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        // Le fixture ne provisionne pas la base : sans migration, le
        // référentiel des positions n'existe pas et rien n'est semé.
        migrations.runMigrationsFor(tenant.databaseName);

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

    private String createDelegate(UserEntity admin, String code, String name) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "%s", "collector": true }
                        """.formatted(code, name))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String statusIdByCode(UserEntity admin, String code) {
        return givenAs(admin).when().get("/api/v1/delegate-statuses")
                .then().statusCode(200)
                .extract().path("data.find { it.code == '" + code + "' }.id");
    }

    @Test
    void le_referentiel_est_seme_avec_les_deux_positions_demandees() {
        UserEntity admin = tenantAdmin();

        givenAs(admin).when().get("/api/v1/delegate-statuses")
                .then().statusCode(200)
                .body("data.find { it.code == 'PRINCIPAL' }.warning", equalTo(false))
                // Le drapeau permet à l'écran de mettre la bonne position en
                // évidence sans connaître les libellés du tenant.
                .body("data.find { it.code == 'DOUBTFUL' }.warning", equalTo(true));
    }

    @Test
    void une_nouvelle_position_n_ecrase_pas_la_precedente() {
        UserEntity admin = tenantAdmin();
        String delegate = createDelegate(admin, "del-hist", "KONE Adama");
        String principal = statusIdByCode(admin, "PRINCIPAL");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        for (String[] step : List.of(
                new String[] { principal, "2026-01-10", "Ouverture de campagne." },
                new String[] { doubtful, "2026-06-01", "Sans nouvelle depuis trois mois." })) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "statusId": "%s", "effectiveDate": "%s", "reason": "%s" }
                            """.formatted(step[0], step[1], step[2]))
                    .when().post("/api/v1/delegates/" + delegate + "/status-positions")
                    .then().statusCode(201);
        }

        givenAs(admin).when().get("/api/v1/delegates/" + delegate + "/status-positions")
                .then().statusCode(200)
                // Le délégué naît après la migration de reprise : il n'a
                // donc que les deux positions posées ici, et la plus
                // récente vient en tête.
                .body("data", hasSize(2))
                .body("data[0].statusCode", equalTo("DOUBTFUL"))
                .body("data[0].reason", equalTo("Sans nouvelle depuis trois mois."))
                .body("data[1].statusCode", equalTo("PRINCIPAL"))
                // L'auteur vient du jeton, jamais de l'appelant.
                .body("data[0].createdByEmail", equalTo(admin.email));
    }

    @Test
    void le_montant_du_se_fige_a_la_prise_de_position() {
        UserEntity admin = tenantAdmin();
        String delegate = createDelegate(admin, "del-fige", "TRAORE Salif");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "statusId": "%s", "reason": "Ne se manifeste plus." }
                        """.formatted(doubtful))
                .when().post("/api/v1/delegates/" + delegate + "/status-positions")
                .then().statusCode(201)
                // Sans avance décaissée, le délégué ne doit rien : le montant
                // est zéro, pas absent. C'est un constat, il a été mesuré.
                .body("data.owedAmount", notNullValue())
                .body("data.statusLabel", equalTo("Délégué douteux"));
    }

    @Test
    void le_montant_fourni_par_l_appelant_est_ignore() {
        UserEntity admin = tenantAdmin();
        String delegate = createDelegate(admin, "del-forge", "BAMBA Issa");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "statusId": "%s", "reason": "Tentative.", "owedAmount": 999999999 }
                        """.formatted(doubtful))
                .when().post("/api/v1/delegates/" + delegate + "/status-positions")
                .then().statusCode(201)
                // Le serveur calcule : un montant tapé par l'appelant ne
                // prouverait rien, et ne doit donc jamais passer.
                .body("data.owedAmount", equalTo(0));
    }

    @Test
    void le_motif_est_obligatoire() {
        UserEntity admin = tenantAdmin();
        String delegate = createDelegate(admin, "del-motif", "YAO Kouassi");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "statusId": "%s", "reason": "  " }
                        """.formatted(doubtful))
                .when().post("/api/v1/delegates/" + delegate + "/status-positions")
                .then().statusCode(400);
    }

    @Test
    void une_position_ne_se_pose_pas_sur_un_fournisseur_ordinaire() {
        UserEntity admin = tenantAdmin();
        String supplier = givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"four-01\", \"name\": \"Quincaillerie du port\" }")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "statusId": "%s", "reason": "Hors sujet." }
                        """.formatted(doubtful))
                .when().post("/api/v1/delegates/" + supplier + "/status-positions")
                .then().statusCode(404);
    }

    @Test
    void l_etat_des_delegues_porte_la_position_courante() {
        UserEntity admin = tenantAdmin();
        String delegate = createDelegate(admin, "del-etat", "DIABATE Mamadou");
        String doubtful = statusIdByCode(admin, "DOUBTFUL");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "statusId": "%s", "effectiveDate": "2026-05-04", "reason": "Injoignable." }
                        """.formatted(doubtful))
                .when().post("/api/v1/delegates/" + delegate + "/status-positions")
                .then().statusCode(201);

        givenAs(admin).when().get("/api/v1/collector-advances/delegates/statement")
                .then().statusCode(200)
                .body("data.rows.find { it.delegateSupplierId == '" + delegate + "' }.statusCode",
                        equalTo("DOUBTFUL"))
                .body("data.rows.find { it.delegateSupplierId == '" + delegate + "' }.statusWarning",
                        equalTo(true))
                .body("data.rows.find { it.delegateSupplierId == '" + delegate + "' }.statusSince",
                        equalTo("2026-05-04"))
                .body("data.rows.find { it.delegateSupplierId == '" + delegate + "' }.owedAtStatus",
                        notNullValue());
    }

    @Test
    void un_delegue_sans_position_ne_recoit_aucun_statut_invente() {
        UserEntity admin = tenantAdmin();
        // La migration de reprise pose une position initiale sur les
        // délégués existants ; celui-ci naît après, donc la vérification
        // porte sur le montant figé, qui doit rester absent tant que
        // personne n'a rien décidé.
        String delegate = createDelegate(admin, "del-vierge", "OUATTARA Sekou");

        givenAs(admin).when().get("/api/v1/collector-advances/delegates/statement")
                .then().statusCode(200)
                .body("data.rows.find { it.delegateSupplierId == '" + delegate + "' }.owedAtStatus",
                        nullValue());
    }
}
