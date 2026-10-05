package com.ntech.cabosse.cashforecast;

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

import java.time.Instant;
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Ce que la structure prévoit de décaisser le mois prochain.
 *
 * <p>Le directeur dépose ses prévisions avant le dernier jour ouvré du
 * mois en cours, le conseil se prononce avant que l'argent ne parte
 * (demandé le 03/10/2026). Valider une commande n'est pas la payer, et
 * une trésorerie se tend quand personne ne regarde le mois d'avance.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CashForecastTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-prev-" + TestFixtures.randomSlugSuffix(), "Coopérative Prévisionnel");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Prévisionnel";
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

    private static final String NEXT_MONTH = YearMonth.now().plusMonths(1).toString();

    /** Un prévisionnel à deux lignes, déposé pour le mois qui vient. */
    private String file(UserEntity a, int first, int second) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "month": "%s",
                          "openingCash": 500000, "openingBank": 4000000,
                          "expectedReceipts": 12000000,
                          "lines": [
                            { "account": "601", "accountLabel": "Achats de marchandises",
                              "detail": "achat de cacao", "amount": %d, "source": "Banque" },
                            { "account": "66", "accountLabel": "Charges de personnel",
                              "amount": %d, "source": "Caisse" },
                            { "account": "625", "accountLabel": "Primes d'assurance" }
                          ] }
                        """.formatted(NEXT_MONTH, first, second))
                .when().put("/api/v1/cash-forecasts")
                .then().statusCode(200).extract().path("data.id");
    }

    @Test
    void le_previsionnel_totalise_et_projette_le_solde() {
        UserEntity a = admin();
        file(a, 9000000, 1500000);

        givenAs(a).when().get("/api/v1/cash-forecasts")
                .then().statusCode(200)
                .body("data", hasSize(1))
                // La ligne sans montant est écartée : le modèle les
                // porte toutes, et les garder ferait un état de trente
                // lignes à zéro.
                .body("data[0].lines", hasSize(2))
                .body("data[0].totalOutflow", equalTo(10500000))
                .body("data[0].openingTotal", equalTo(4500000))
                // 4 500 000 + 12 000 000 − 10 500 000 : c'est le chiffre
                // que le conseil regarde.
                .body("data[0].projectedBalance", equalTo(6000000))
                .body("data[0].status", equalTo("DRAFT"));
    }

    @Test
    void la_source_des_fonds_se_lit_en_toutes_lettres() {
        UserEntity a = admin();
        String id = file(a, 1000, 2000);

        // Le fichier du conseil écrit « Banque » et « Caisse » : les
        // refuser obligerait à le réécrire à chaque mois.
        givenAs(a).when().get("/api/v1/cash-forecasts/" + id)
                .then().statusCode(200)
                .body("data.lines[0].source", equalTo("BANK"))
                .body("data.lines[1].source", equalTo("CASH"));
    }

    @Test
    void recharger_un_mois_remplace_au_lieu_d_ajouter() {
        UserEntity a = admin();
        file(a, 1000000, 500000);
        file(a, 2000000, 500000);

        // Deux prévisionnels pour un mois laisseraient le conseil
        // approuver l'un et lire l'autre.
        givenAs(a).when().get("/api/v1/cash-forecasts")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].totalOutflow", equalTo(2500000));
    }

    @Test
    void le_conseil_ne_se_prononce_pas_sur_un_brouillon() {
        UserEntity a = admin();
        String id = file(a, 1000000, 0);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/approve")
                .then().statusCode(422);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/submit")
                .then().statusCode(200).body("data.status", equalTo("SUBMITTED"));

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));
    }

    @Test
    void un_previsionnel_soumis_ne_se_modifie_plus() {
        UserEntity a = admin();
        String id = file(a, 1000000, 0);
        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/submit").then().statusCode(200);

        // Le conseil lit : corriger sous ses yeux ferait porter sa
        // décision sur un document qui a changé.
        givenAs(a).contentType("application/json")
                .body("""
                        { "month": "%s", "lines": [
                            { "account": "601", "amount": 99 } ] }
                        """.formatted(NEXT_MONTH))
                .when().put("/api/v1/cash-forecasts").then().statusCode(422);
    }

    @Test
    void un_refus_rouvre_le_previsionnel_a_la_correction() {
        UserEntity a = admin();
        String id = file(a, 1000000, 0);
        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/submit").then().statusCode(200);

        givenAs(a).contentType("application/json")
                .body("{\"reason\":\"Le poste carburant est hors budget\"}")
                .when().post("/api/v1/cash-forecasts/" + id + "/reject")
                .then().statusCode(200)
                .body("data.status", equalTo("REJECTED"))
                .body("data.rejectionReason", equalTo("Le poste carburant est hors budget"));

        // Obliger à tout recharger pour une ligne perdrait les autres :
        // le prévisionnel se reprend et repart au brouillon.
        givenAs(a).contentType("application/json")
                .body("""
                        { "month": "%s", "lines": [
                            { "account": "601", "amount": 800000 } ] }
                        """.formatted(NEXT_MONTH))
                .when().put("/api/v1/cash-forecasts")
                .then().statusCode(200)
                .body("data.status", equalTo("DRAFT"))
                .body("data.rejectionReason", nullValue());
    }

    @Test
    void un_previsionnel_vide_ne_se_soumet_pas() {
        UserEntity a = admin();
        String id = givenAs(a).contentType("application/json")
                .body("{ \"month\": \"" + NEXT_MONTH + "\", \"lines\": [] }")
                .when().put("/api/v1/cash-forecasts")
                .then().statusCode(200).extract().path("data.id");

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/cash-forecasts/" + id + "/submit")
                .then().statusCode(422);
    }

    @Test
    void le_modele_porte_les_comptes_du_conseil() {
        UserEntity a = admin();

        String csv = givenAs(a).when().get("/api/v1/cash-forecasts/template?format=csv")
                .then().statusCode(200).extract().asString();

        // Les comptes sont pré-remplis dans l'ordre du classeur : le
        // conseil les relit depuis des années.
        org.assertj.core.api.Assertions.assertThat(csv)
                .contains("N° compte du plan comptable")
                .contains("Achats de marchandises")
                .contains("Charges de personnel")
                .contains("Pertes de change financières");
    }
}
