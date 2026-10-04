package com.ntech.cabosse.expense;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Charger les dépenses d'un mois depuis un tableur.
 *
 * <p>Les saisir une à une tenait tant qu'elles étaient rares. Une
 * structure qui relève trente lignes de carburant, de téléphone et de
 * fournitures en fin de mois ne le fait pas trente fois à la main
 * (demandé le 04/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DirectExpenseImportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-impdep-" + TestFixtures.randomSlugSuffix(), "Coopérative Import Dépenses");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Import";
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

    @Test
    void le_modele_porte_les_colonnes_attendues() {
        UserEntity a = admin();

        String csv = givenAs(a).when().get("/api/v1/direct-expenses/import/template?format=csv")
                .then().statusCode(200).extract().asString();

        // Le modèle est le contrat avec celui qui prépare le fichier :
        // une colonne qui n'y figure pas ne sera jamais remplie.
        for (String header : java.util.List.of(
                "Nature", "Date", "Prestataire", "Type de dépense", "Compte de charge",
                "Libellé", "Montant HT", "Taux TVA")) {
            org.assertj.core.api.Assertions.assertThat(csv)
                    .as("colonne « %s » absente du modèle", header).contains(header);
        }
    }

    @Test
    void l_apercu_annonce_ce_que_le_fichier_engage() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Abonnement", "expenseDate": "04/10/2026",
                            "chargeAccount": "605000", "label": "Électricité",
                            "amountHt": "100000", "vatRatePct": "18" },
                          { "rowNumber": 2, "kind": "Petite dépense", "expenseDate": "04/10/2026",
                            "chargeAccount": "628000", "label": "Crédit téléphonique",
                            "amountHt": "5 000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(2))
                .body("data.invalidRows", equalTo(0))
                // 118 000 et 5 000 : la caisse saura ce qu'elle engage
                // avant que rien ne soit écrit.
                .body("data.totalAmount", equalTo(123000));
    }

    @Test
    void la_nature_se_lit_en_toutes_lettres_comme_en_code() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "CONTRACT", "chargeAccount": "605000",
                            "label": "Internet", "amountHt": "50000" },
                          { "rowNumber": 2, "kind": "abonnement", "chargeAccount": "605000",
                            "label": "Eau", "amountHt": "30000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(2))
                .body("data.rows[0].normalized.kind", equalTo("CONTRACT"))
                .body("data.rows[1].normalized.kind", equalTo("CONTRACT"));
    }

    @Test
    void un_prestataire_inconnu_ne_fait_pas_echouer_la_ligne() {
        UserEntity a = admin();

        // Refuser une petite dépense faute de fiche arrêterait la caisse
        // sur un achat de crédit téléphonique : la ligne passe, et la
        // dette reste au collectif.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Petite dépense", "supplierName": "Taxi du marché",
                            "chargeAccount": "628000", "label": "Transport", "amountHt": "3000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.rows[0].notices", hasSize(1));
    }

    @Test
    void une_ligne_sans_compte_de_charge_est_refusee() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Petite dépense",
                            "label": "Sans compte", "amountHt": "1000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/preview")
                .then().statusCode(200)
                .body("data.invalidRows", equalTo(1))
                .body("data.rows[0].status", equalTo("INVALID"));
    }

    @Test
    void le_fichier_applique_cree_des_depenses_qui_attendent_leur_reglement() {
        UserEntity a = admin();
        String payload = """
                [ { "rowNumber": 1, "kind": "Abonnement", "chargeAccount": "605000",
                    "label": "Électricité", "amountHt": "100000", "vatRatePct": "18" },
                  { "rowNumber": 2, "kind": "Petite dépense", "chargeAccount": "628000",
                    "label": "Fournitures", "amountHt": "5000" } ]
                """;

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/direct-expenses/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(2))
                .body("data.skippedCount", equalTo(0));

        // Elles se constatent, elles ne se règlent pas : la file les
        // attend, chacune sous sa nature.
        givenAs(a).when().get("/api/v1/treasury/payables?kind=SUBSCRIPTION")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(118000));
        givenAs(a).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200).body("data.page.items", hasSize(1));
    }

    @Test
    void une_ligne_refusee_n_empeche_pas_les_autres() {
        UserEntity a = admin();

        // Tout annuler pour une ligne obligerait à relancer trente
        // dépenses pour une seule erreur.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Petite dépense", "chargeAccount": "628000",
                            "label": "Bonne ligne", "amountHt": "1000" },
                          { "rowNumber": 2, "kind": "Petite dépense", "chargeAccount": "628000",
                            "label": "Sans montant" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.skippedCount", equalTo(1))
                .body("data.skippedRows[0].rowNumber", equalTo(2));
    }
}
