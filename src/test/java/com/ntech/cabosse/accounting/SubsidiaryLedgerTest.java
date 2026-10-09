package com.ntech.cabosse.accounting;

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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * La comptabilité auxiliaire (demandé le 29/09/2026).
 *
 * <p>Le compte collectif donne le total dû par l'ensemble des clients,
 * le compte auxiliaire dit ce que doit celui-ci. Sans le second, le
 * grand livre ne répond pas à « combien nous doit ce client », qui est
 * la question posée chaque semaine.</p>
 *
 * <p>Un ERP ne mouvemente pas les deux comptes à la fois, sans quoi
 * chaque montant compterait double : l'écriture va sur le compte du
 * tiers quand il en a un, sur le collectif sinon. Le solde propre du
 * collectif est donc ce qui reste porté par des tiers sans compte, et
 * l'état le montre au lieu de l'additionner en silence.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SubsidiaryLedgerTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-aux-" + TestFixtures.randomSlugSuffix(), "Coopérative Auxiliaires");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Auxiliaires";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    /** Un client qui a son propre compte, rattaché au collectif des clients. */
    private String customerWithAccount(UserEntity a, String name, String account) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "name": "%s", "type": "COMPANY",
                          "subsidiaryAccount": "%s", "collectiveAccount": "411000" }
                        """.formatted(name, account))
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
    }

    /**
     * Une écriture au journal : deux lignes, un tiers, une contrepartie.
     *
     * <p>Passée en à-nouveau puis validée, c'est le chemin le plus court
     * pour poser une pièce sans monter tout un cycle d'achat ou de
     * vente. Ce qui est éprouvé ici est la lecture, pas la façon dont
     * l'écriture est née.</p>
     */
    private void entry(UserEntity a, String debit, String credit, int amount, String label) {
        String id = givenAs(a).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "%s", "lines": [
                            { "account": "%s", "libelle": "%s", "debit": %d },
                            { "account": "%s", "libelle": "%s", "credit": %d }
                          ] }
                        """.formatted(LocalDate.now(), label, debit, label, amount,
                                credit, label, amount))
                .when().post("/api/v1/accounting/opening-entries").then().statusCode(201)
                .extract().path("data.id");
        givenAs(a).when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    @Test
    void l_import_pose_les_comptes_du_tiers() {
        UserEntity a = admin();

        // Les colonnes sont au modèle, donc elles doivent arriver jusqu'à
        // la fiche : un champ qu'on propose de remplir et qui se perd en
        // route est pire que pas de champ du tout.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "name": "Zamacom", "type": "Entreprise",
                            "subsidiaryAccount": " 411001 ", "collectiveAccount": "411000" } ]
                        """)
                .when().post("/api/v1/customers/import/commit").then().statusCode(200);

        givenAs(a).queryParam("q", "Zamacom").when().get("/api/v1/customers")
                .then().statusCode(200)
                // L'espace autour du numéro tombe : deux comptes qui ne
                // diffèrent que par elle casseraient le rapprochement.
                .body("data.items[0].subsidiaryAccount", equalTo("411001"))
                .body("data.items[0].collectiveAccount", equalTo("411000"));
    }

    @Test
    void chaque_tiers_se_lit_sous_son_collectif() {
        UserEntity a = admin();
        customerWithAccount(a, "Kouassi", "411001");
        customerWithAccount(a, "Traoré", "411002");
        entry(a, "411001", "521000", 500000, "Facture Kouassi");
        entry(a, "411002", "521000", 300000, "Facture Traoré");

        String group = "data.groups.find { it.collectiveAccount == '411000' }";
        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body(group + ".parties.account", hasItem("411001"))
                .body(group + ".parties.partyName", hasItem("Kouassi"))
                // Le contrôle : le détail par tiers fait bien le total.
                .body(group + ".partiesBalance", equalTo(800000))
                .body(group + ".unallocated", equalTo(0))
                .body(group + ".total", equalTo(800000));
    }

    /**
     * Un compte partagé par plusieurs fiches ne fait qu'une ligne.
     *
     * <p>Chaque fiche lisait le solde de son compte et l'affichait pour
     * elle seule : trois fiches sur un même compte « divers » montraient
     * trois fois le même solde, et le total du collectif les
     * additionnait (relevé le 09/10/2026).</p>
     */
    @Test
    void un_compte_partage_par_plusieurs_fiches_ne_fait_qu_une_ligne() {
        UserEntity a = admin();
        for (String name : new String[]{"Divers A", "Divers B", "Divers C"}) {
            customerWithAccount(a, name, "411040");
        }
        entry(a, "411040", "521000", 11000, "Facture divers");

        String group = "data.groups.find { it.collectiveAccount == '411000' }";
        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body(group + ".parties.findAll { it.account == '411040' }", hasSize(1))
                // 11 000 une fois, pas trois.
                .body(group + ".partiesBalance", equalTo(11000))
                .body(group + ".total", equalTo(11000));
    }

    /** Des noms qui diffèrent sur un même compte : le plan les nomme. */
    @Test
    void des_fiches_de_noms_differents_prennent_le_libelle_du_compte() {
        UserEntity a = admin();
        // Ouvrir un compte au plan allume le contrôle d'existence sur
        // toute l'écriture : la contrepartie doit y figurer aussi.
        for (String[] account : new String[][]{
                {"411050", "Clients divers"}, {"521000", "Banque"}}) {
            givenAs(a).contentType("application/json")
                    .body("{\"number\":\"%s\",\"label\":\"%s\"}"
                            .formatted(account[0], account[1]))
                    .when().post("/api/v1/accounting/chart").then().statusCode(201);
        }
        customerWithAccount(a, "Kouadio", "411050");
        customerWithAccount(a, "Yao", "411050");
        entry(a, "411050", "521000", 7000, "Facture divers");

        // Aucune des deux fiches ne peut prétendre nommer le solde à elle
        // seule : c'est le compte qui le nomme.
        String group = "data.groups.find { it.collectiveAccount == '411000' }";
        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body(group + ".parties.find { it.account == '411050' }.partyName",
                        equalTo("Clients divers"));
    }

    @Test
    void ce_que_le_collectif_porte_seul_se_voit() {
        UserEntity a = admin();
        customerWithAccount(a, "Kouassi", "411001");
        entry(a, "411001", "521000", 500000, "Facture Kouassi");
        // Un client sans compte à lui : son montant reste sur le
        // collectif. Le taire ferait croire que le détail est complet.
        entry(a, "411000", "521000", 200000, "Facture client sans compte");

        String group = "data.groups.find { it.collectiveAccount == '411000' }";
        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body(group + ".partiesBalance", equalTo(500000))
                .body(group + ".unallocated", equalTo(200000))
                .body(group + ".total", equalTo(700000));
    }

    @Test
    void un_compte_de_tiers_que_personne_ne_revendique_est_signale() {
        UserEntity a = admin();
        // Un compte de classe 4 mouvementé sans qu'aucune fiche ne le
        // porte : l'écriture existe, mais on ne sait pas pour qui.
        entry(a, "411009", "521000", 150000, "Facture sans fiche");

        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body("data.orphans.account", hasItem("411009"))
                // Une charge n'a pas de tiers : elle n'a rien à faire ici.
                .body("data.orphans.account", not(hasItem("521000")));
    }

    @Test
    void un_collectif_de_tiers_connu_n_est_pas_pris_pour_un_orphelin() {
        UserEntity a = admin();
        // Les avances de collecte sont un compte de tiers, tenu à
        // dessein : le signaler comme sans fiche ferait chercher une
        // erreur qui n'existe pas.
        entry(a, "471100", "521000", 400000, "Avance de collecte");

        givenAs(a).when().get("/api/v1/accounting/subsidiary-balance")
                .then().statusCode(200)
                .body("data.orphans.account", not(hasItem("471100")))
                .body("data.groups.collectiveAccount", hasItem("471100"));
    }

    @Test
    void une_facture_et_son_reglement_portent_la_meme_lettre() {
        UserEntity a = admin();
        customerWithAccount(a, "Kouassi", "411001");
        entry(a, "411001", "521000", 500000, "Facture");
        entry(a, "571000", "411001", 500000, "Règlement");
        entry(a, "411001", "521000", 120000, "Facture non réglée");

        givenAs(a).when().get("/api/v1/accounting/subsidiary-ledger/411001")
                .then().statusCode(200)
                .body("data.rows[0].letter", equalTo("A"))
                .body("data.rows[1].letter", equalTo("A"))
                // Ce qui reste sans lettre est ce qui reste dû, et c'est
                // le seul chiffre qu'on relance.
                .body("data.rows[2].letter", nullValue())
                .body("data.openBalance", equalTo(120000));
    }

    @Test
    void un_reglement_partiel_ne_solde_rien() {
        UserEntity a = admin();
        customerWithAccount(a, "Kouassi", "411001");
        entry(a, "411001", "521000", 500000, "Facture");
        entry(a, "571000", "411001", 200000, "Acompte");

        // Rapprocher sur un montant qui ne correspond pas ferait
        // disparaître une créance qui existe encore : les deux lignes
        // restent ouvertes, et le dû est bien ce qui reste.
        givenAs(a).when().get("/api/v1/accounting/subsidiary-ledger/411001")
                .then().statusCode(200)
                .body("data.rows[0].letter", nullValue())
                .body("data.rows[1].letter", nullValue())
                .body("data.openBalance", equalTo(300000));
    }
}
