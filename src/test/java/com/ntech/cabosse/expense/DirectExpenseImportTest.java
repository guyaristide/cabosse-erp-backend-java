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
                "Nature", "Date", "Prestataire", "N° compte prestataire", "Type de dépense",
                "Compte de charge", "Libellé", "Montant HT", "Taux TVA")) {
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
    void un_prestataire_absent_voit_sa_fiche_ouverte() {
        UserEntity a = admin();

        // Refuser une petite dépense faute de fiche arrêterait la caisse
        // sur un achat de crédit téléphonique. L'aperçu l'annonce, sans
        // rien écrire.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Petite dépense", "supplierName": "Taxi du marché",
                            "supplierAccount": "401450",
                            "chargeAccount": "628000", "label": "Transport", "amountHt": "3000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.rows[0].normalized.supplierWillBeCreated", equalTo(true))
                .body("data.rows[0].normalized.supplierAccount", equalTo("401450"))
                .body("data.rows[0].notices", hasSize(1));

        // Rien n'a été écrit par l'aperçu.
        givenAs(a).queryParam("q", "Taxi").when().get("/api/v1/suppliers")
                .then().statusCode(200).body("data.items", hasSize(0));
    }

    @Test
    void le_compte_du_fichier_ouvre_la_fiche_du_prestataire() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Abonnement", "supplierName": "Compagnie des eaux",
                            "supplierAccount": "401310",
                            "chargeAccount": "605000", "label": "Eau", "amountHt": "40000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.createdSupplierCount", equalTo(1));

        // Le compte du fichier décide où se loge la dette envers ce tiers.
        givenAs(a).queryParam("q", "Compagnie des eaux").when().get("/api/v1/suppliers")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].subsidiaryAccount", equalTo("401310"));
    }

    @Test
    void le_fichier_ne_remplace_pas_un_compte_deja_ouvert() {
        UserEntity a = admin();
        givenAs(a).contentType("application/json")
                .body("""
                        { "name": "Compagnie d'électricité", "subsidiaryAccount": "401200" }
                        """)
                .when().post("/api/v1/suppliers").then().statusCode(201);

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "kind": "Abonnement",
                            "supplierName": "Compagnie d'électricité", "supplierAccount": "401999",
                            "chargeAccount": "605000", "label": "Électricité", "amountHt": "10000" } ]
                        """)
                .when().post("/api/v1/direct-expenses/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.createdSupplierCount", equalTo(0));

        // Changer le compte depuis un fichier déplacerait toute la dette
        // de ce tiers sans que personne ne l'ait décidé.
        givenAs(a).queryParam("q", "Compagnie d").when().get("/api/v1/suppliers")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].subsidiaryAccount", equalTo("401200"));
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

    /**
     * Le fichier réel du client, rejoué tel quel.
     *
     * <p>Signalé le 09/10/2026 : les dépenses importées se voyaient à
     * l'écran des dépenses mais n'arrivaient ni dans la file
     * d'approbation ni dans celle de la caisse. Le fichier porte un
     * prestataire et son compte, ce que l'exemple du modèle n'avait
     * pas.</p>
     */
    @Test
    void le_fichier_du_terrain_arrive_bien_dans_la_file_a_payer() {
        UserEntity a = admin();
        String payload = """
                [ { "rowNumber": 1, "kind": "Petite dépense", "expenseDate": "06/10/2026",
                    "supplierName": "Caisse KKO", "supplierAccount": "571200",
                    "expenseTypeName": "Transfert de fonds", "chargeAccount": "571100",
                    "label": "Virement caisse fonctionnement vers caisse achat KKO",
                    "amountHt": "470000", "vatRatePct": "0" },
                  { "rowNumber": 2, "kind": "Petite dépense", "expenseDate": "06/10/2026",
                    "supplierName": "Fournisseur Divers", "supplierAccount": "401040",
                    "expenseTypeName": "Transport administratif", "chargeAccount": "618100",
                    "label": "Corridor Guingolo", "amountHt": "500", "vatRatePct": "0" },
                  { "rowNumber": 3, "kind": "Petite dépense", "expenseDate": "06/10/2026",
                    "supplierName": "JEBACO SARL DUEKOUE", "supplierAccount": "401030",
                    "expenseTypeName": "Matériaux", "chargeAccount": "604000",
                    "label": "Achat de 20 planches", "amountHt": "212100", "vatRatePct": "0" } ]
                """;

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/direct-expenses/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(3));

        // Aucun circuit d'approbation réglé : elles sont payables tout
        // de suite, et la caisse doit les voir.
        givenAs(a).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200)
                .body("data.page.items", hasSize(3));
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
