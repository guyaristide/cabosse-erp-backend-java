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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Dépenses directes sans bon de livraison (backlog ACH-03) : petite caisse
 * (charge / caisse) et contrat/abonnement (charge + TVA déductible / banque),
 * comptabilisées sans réception ni mouvement de stock.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DirectExpenseTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dep-" + TestFixtures.randomSlugSuffix(), "Coopérative Dépenses");
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

    @Test
    void une_petite_depense_se_constate_sans_sortir_d_argent() {
        UserEntity admin = tenantAdmin();
        // Une caisse vide ne paie rien : la structure y met d'abord son
        // solde d'ouverture.
        fundCashBox(admin, 100000);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "chargeAccount": "628000",
                          "label": "Fournitures de nettoyage", "amountHt": 5000,
                          "paymentMethod": "CASH", "expenseDate": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201)
                .body("data.ref", equalTo("DEP-" + LocalDate.now().getYear() + "-0001"))
                .body("data.amountTtc", equalTo(5000))
                // Le prestataire n'est pas nommé : la dette va au
                // collectif fournisseurs. La refuser pour autant
                // arrêterait la caisse sur un achat de crédit
                // téléphonique.
                .body("data.payableAccount", equalTo("401000"));

        // Deux pièces : l'amorçage de la caisse, puis la dépense. La
        // dépense est la plus récente, donc en tête.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(2))
                .body("data.items[0].sourceType", equalTo("DIRECT_EXPENSE"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("628000"))
                // La caisse n'a pas bougé : la dépense se constate, elle
                // se règle depuis la trésorerie (demandé le 03/10/2026).
                .body("data.items[0].entries.syscohadaAccount", hasItem("401000"))
                .body("data.items[0].entries.syscohadaAccount", not(hasItem("571000")));
    }

    @Test
    void une_depense_sur_contrat_porte_sa_dette_au_prestataire() {
        UserEntity admin = tenantAdmin();

        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Compagnie d'électricité\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CONTRACT", "supplierId": "%s", "chargeAccount": "627000",
                          "label": "Électricité", "periodLabel": "Juillet 2026",
                          "amountHt": 100000, "vatRatePct": 18,
                          "paymentMethod": "BANK_TRANSFER", "expenseDate": "%s" }
                        """.formatted(supplierId, LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201)
                .body("data.vatAmount", equalTo(18000))
                .body("data.amountTtc", equalTo(118000))
                .body("data.payableAccount", equalTo("401000"));

        // Débit 627000 (HT) + 445660 (TVA) / crédit du compte du tiers.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].entries.syscohadaAccount", hasItem("627000"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("445660"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("401000"))
                .body("data.items[0].entries.syscohadaAccount", not(hasItem("521000")));
    }

    @Test
    void la_depense_constatee_attend_dans_la_file_a_payer() {
        UserEntity admin = tenantAdmin();
        fundCashBox(admin, 100000);
        createPettyCash(admin, 5000);

        // Valider une dépense n'est pas la payer : la caisse arbitre ses
        // priorités, et la file est l'endroit où elle le fait.
        givenAs(admin).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(5000));
    }

    @Test
    void le_reglement_sort_l_argent_et_vide_la_file() {
        UserEntity admin = tenantAdmin();
        fundCashBox(admin, 100000);
        String id = createPettyCash(admin, 5000);

        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(200);

        // C'est maintenant que la caisse se vide, et la dépense quitte
        // la file.
        givenAs(admin).when().get("/api/v1/accounting/journal?search=DEP-")
                .then().statusCode(200)
                .body("data.items.find { it.sourceType == 'DIRECT_EXPENSE_PAYMENT' }"
                        + ".entries.syscohadaAccount", hasItem("571000"));
        givenAs(admin).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200).body("data.page.items", hasSize(0));
    }

    @Test
    void un_reglement_partiel_laisse_le_reste_dans_la_file() {
        UserEntity admin = tenantAdmin();
        fundCashBox(admin, 100000);
        String id = createPettyCash(admin, 5000);

        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CASH\",\"amount\":2000}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(200);

        // Ce qui reste dû garde sa place : une dépense à moitié payée
        // qui disparaîtrait de la file ne serait jamais soldée.
        givenAs(admin).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(3000));
    }

    @Test
    void on_ne_paie_pas_plus_que_ce_qui_reste_du() {
        UserEntity admin = tenantAdmin();
        fundCashBox(admin, 100000);
        String id = createPettyCash(admin, 5000);

        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CASH\",\"amount\":9000}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(422);
    }

    /** Une petite dépense constatée, dont on garde l'identifiant. */
    private String createPettyCash(UserEntity admin, int amount) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "chargeAccount": "628000",
                          "label": "Fournitures", "amountHt": %d,
                          "paymentMethod": "CASH", "expenseDate": "%s" }
                        """.formatted(amount, LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201).extract().path("data.id");
    }

    @Test
    void expense_requires_a_charge_account() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "PETTY_CASH", "label": "Sans compte",
                          "amountHt": 1000, "paymentMethod": "CASH" }
                        """)
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(422);
    }
}
