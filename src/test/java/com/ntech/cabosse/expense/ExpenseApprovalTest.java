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
import static org.hamcrest.Matchers.hasSize;

/**
 * Qui décide avant qu'une dépense ne soit payée.
 *
 * <p>Son propre circuit, distinct de celui des règlements aux
 * producteurs et délégués (tranché le 03/10/2026) : une facture
 * d'électricité et un solde de campagne ne se décident ni par les mêmes
 * personnes ni sur les mêmes montants.</p>
 *
 * <p>Ce que ces tests tiennent : qu'une structure qui n'a rien réglé
 * garde le paiement direct, qu'une dépense en attente ne se règle pas,
 * et que le second échelon ne se confond pas avec le premier.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ExpenseApprovalTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dep-" + TestFixtures.randomSlugSuffix(), "Coopérative Dépenses");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Dépenses";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 10_000_000);
        return u;
    }

    private void setPrefs(UserEntity who, String body) {
        givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private String expense(UserEntity a, String kind, int amount) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "kind": "%s", "chargeAccount": "628000",
                          "label": "Dépense", "amountHt": %d,
                          "paymentMethod": "CASH", "expenseDate": "%s" }
                        """.formatted(kind, amount, LocalDate.now()))
                .when().post("/api/v1/direct-expenses")
                .then().statusCode(201).extract().path("data.id");
    }

    @Test
    void sans_regle_la_depense_se_regle_directement() {
        UserEntity a = admin();
        String id = expense(a, "PETTY_CASH", 5000);

        // Le circuit ne s'impose pas tout seul : une structure qui n'a
        // rien posé continue de payer comme avant.
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(200);
    }

    @Test
    void une_depense_en_attente_ne_se_regle_pas() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0 }");
        String id = expense(a, "PETTY_CASH", 5000);

        // Valider une dépense n'est pas ordonner son paiement : sans
        // décision, la caisse ne sort rien.
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(422);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(200).body("data.approvalStatus", equalTo("APPROVED"));

        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(200);
    }

    @Test
    void la_file_a_payer_ignore_ce_qui_attend_une_decision() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0 }");
        String id = expense(a, "PETTY_CASH", 5000);

        // Elle y figurait avec un bouton que le serveur refusait : le
        // caissier arbitrait sur une somme qui n'était pas accordée
        // (demandé le 04/10/2026).
        givenAs(a).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200).body("data.page.items", hasSize(0));

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(200);

        givenAs(a).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].sourceId", equalTo(id));
    }

    @Test
    void la_depense_qui_attend_remonte_dans_la_file_d_approbation() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0 }");
        String id = expense(a, "PETTY_CASH", 7000);

        // Le circuit existait sans qu'aucun écran ne le serve : une
        // dépense déposée dormait sans que personne ne sache qu'elle
        // attendait (04/10/2026).
        givenAs(a).queryParam("kind", "DIRECT_EXPENSE")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].sourceId", equalTo(id))
                .body("data.page.items[0].amount", equalTo(7000))
                .body("data.page.items[0].actionable", equalTo(true));

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(200);

        givenAs(a).queryParam("kind", "DIRECT_EXPENSE")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200).body("data.page.items", hasSize(0));
    }

    @Test
    void le_second_echelon_reste_dans_la_file_apres_le_premier() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0,"
                + " \"expenseGovernanceThreshold\": 1000 }");
        String id = expense(a, "PETTY_CASH", 50000);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(200);

        // Le premier échelon est passé, la ligne dit que c'est la
        // gouvernance qui manque : sans quoi personne ne sait qui
        // relancer.
        givenAs(a).queryParam("kind", "DIRECT_EXPENSE")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].governanceApprovalRequired", equalTo(true));
    }

    @Test
    void le_perimetre_borne_ce_qui_passe_par_une_decision() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"SUBSCRIPTIONS\","
                + " \"expenseApprovalThreshold\": 0 }");

        // Seuls les abonnements sont visés : une petite dépense garde le
        // paiement direct.
        String petty = expense(a, "PETTY_CASH", 5000);
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + petty + "/pay")
                .then().statusCode(200);

        String subscription = expense(a, "CONTRACT", 5000);
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + subscription + "/pay")
                .then().statusCode(422);
    }

    @Test
    void le_seuil_laisse_passer_les_petits_montants() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\","
                + " \"expenseApprovalThreshold\": 100000 }");

        String small = expense(a, "PETTY_CASH", 5000);
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + small + "/pay")
                .then().statusCode(200);

        String big = expense(a, "PETTY_CASH", 200000);
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + big + "/pay")
                .then().statusCode(422);
    }

    @Test
    void le_second_echelon_ne_se_confond_pas_avec_le_premier() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0,"
                + " \"expenseGovernanceThreshold\": 100000 }");
        String id = expense(a, "PETTY_CASH", 500000);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(200);

        // Le premier accord ne suffit pas au-delà du second seuil : le
        // conseil doit s'être prononcé.
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(422);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve-governance")
                .then().statusCode(200);
        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(200);
    }

    @Test
    void une_depense_refusee_ne_se_regle_jamais() {
        UserEntity a = admin();
        setPrefs(a, "{ \"expenseApprovalScope\": \"ALL\", \"expenseApprovalThreshold\": 0 }");
        String id = expense(a, "PETTY_CASH", 5000);

        givenAs(a).contentType("application/json").body("{\"reason\":\"Hors budget\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/reject")
                .then().statusCode(200).body("data.approvalStatus", equalTo("REJECTED"));

        givenAs(a).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/direct-expenses/" + id + "/pay")
                .then().statusCode(422);
        // Et elle ne repasse pas par un accord : le refus est la
        // décision, pas une étape de plus.
        givenAs(a).contentType("application/json")
                .when().post("/api/v1/direct-expenses/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void la_file_a_payer_separe_abonnements_et_petites_depenses() {
        UserEntity a = admin();
        expense(a, "CONTRACT", 120000);
        expense(a, "PETTY_CASH", 5000);

        // Le caissier arbitre entre une facture d'électricité et un
        // achat de fournitures : il doit lire laquelle il paie.
        givenAs(a).when().get("/api/v1/treasury/payables?kind=SUBSCRIPTION")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(120000));
        givenAs(a).when().get("/api/v1/treasury/payables?kind=PETTY_CASH")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(5000));
    }
}
