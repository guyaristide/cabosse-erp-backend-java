package com.ntech.cabosse.settlement;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

/**
 * L'approbation avant de régler un solde.
 *
 * <p>Demandée par l'expert-comptable le 12/09/2026. Régler un délégué ou
 * un producteur était un geste unique : la caissière enregistrait le
 * paiement, l'argent sortait, aucune décision n'était demandée ni gardée
 * en trace.</p>
 *
 * <p>Tout ce qui relève de la gouvernance est en réglage : qui est
 * concerné, à partir de quel montant, et à partir de quel autre montant
 * le second échelon devient nécessaire. Ces tests vérifient les réglages
 * autant que le circuit.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SettlementApprovalTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-set-" + TestFixtures.randomSlugSuffix(), "Coopérative Règlement");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Règlement";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        givenAs(u).contentType("application/json")
                .body("{\"producerPartialPaymentEnabled\":true}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return u;
    }

    private void setting(UserEntity admin, String json) {
        givenAs(admin).contentType("application/json").body(json)
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private String delegate(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String siteId;
    private String articleId;
    private String memberId;

    /**
     * Une livraison non réglée, qui crée le dû qu'on veut approuver.
     *
     * <p>Site, article et producteur sont créés une fois et réutilisés :
     * le plan borne le nombre de sites, et en ouvrir un par livraison
     * faisait échouer la seconde.</p>
     */
    private String unpaidReceipt(UserEntity admin, String delegateId, long weight, long price) {
        if (siteId == null) {
            siteId = givenAs(admin).contentType("application/json")
                    .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"m-"
                            + TestFixtures.randomSlugSuffix() + "\"}")
                    .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
            articleId = givenAs(admin).contentType("application/json")
                    .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao\",\"unit\":\"kg\"}")
                    .when().post("/api/v1/articles").then().statusCode(201)
                    .extract().path("data.id");
            memberId = givenAs(admin).contentType("application/json")
                    .body("{\"lastName\":\"KOUAME\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                    .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        }
        return givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKg": %d, "amountPaid": 0,
                          "paymentMethod": "BANK_TRANSFER", "delegateSupplierId": "%s" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                        weight, price, delegateId))
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse pay(UserEntity admin, String delegateId,
                                                            String purchaseId, long amount) {
        return givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "delegateSupplierId": "%s", "paymentMethod": "BANK_TRANSFER",
                          "date": "%s", "allocations": [ { "purchaseId": "%s", "amount": %d } ] }
                        """.formatted(delegateId, LocalDate.now(), purchaseId, amount))
                .when().post("/api/v1/producer-payments").then();
    }

    // ─── Le réglage commande ────────────────────────────────────────

    /**
     * Rien n'est configuré : le règlement reste un geste direct. Une
     * structure qui n'a rien décidé ne doit rien voir changer.
     */
    @Test
    void without_a_setting_a_settlement_still_goes_straight_through() {
        UserEntity admin = tenantAdmin();
        String delegateId = delegate(admin, "Délégué Direct");
        String purchaseId = unpaidReceipt(admin, delegateId, 1000, 1000);

        pay(admin, delegateId, purchaseId, 1_000_000).statusCode(201);
    }

    /** Sous le seuil, on règle sans demander. Au-dessus, il faut une décision. */
    @Test
    void the_threshold_decides_who_needs_an_approval() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":2000000}");
        String delegateId = delegate(admin, "Délégué Seuil");
        String small = unpaidReceipt(admin, delegateId, 1000, 1000);
        String large = unpaidReceipt(admin, delegateId, 5000, 1000);

        // 1 000 000 : sous le seuil, rien ne change.
        pay(admin, delegateId, small, 1_000_000).statusCode(201);

        // 5 000 000 : au-dessus, la caisse refuse tant que rien n'est décidé.
        pay(admin, delegateId, large, 5_000_000).statusCode(422);
    }

    /** Le périmètre passe avant le seuil : hors périmètre, aucun seuil ne mord. */
    @Test
    void a_scope_limited_to_delegates_leaves_producers_alone() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"MEMBERS\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué Hors Périmètre");
        String purchaseId = unpaidReceipt(admin, delegateId, 5000, 1000);

        // Le périmètre ne vise que les producteurs : le délégué passe.
        pay(admin, delegateId, purchaseId, 5_000_000).statusCode(201);
    }

    // ─── Le circuit ─────────────────────────────────────────────────

    @Test
    void a_request_is_approved_then_paid_and_never_twice() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué Circuit");
        String purchaseId = unpaidReceipt(admin, delegateId, 5000, 1000);

        String requestId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "beneficiaryName": "Délégué Circuit",
                          "amount": 5000000, "paymentMethod": "BANK_TRANSFER",
                          "notes": "Solde de la livraison" }
                        """.formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .extract().path("data.id");

        // Elle attend dans la file de celui qui décide, avec les avances.
        List<String> kinds = givenAs(admin).when().get("/api/v1/governance/approvals")
                .then().statusCode(200).extract().path("data.page.items.kind");
        assertThat(kinds).contains("SETTLEMENT_REQUEST");

        // Une seconde demande sur le même bénéficiaire ferait sortir deux
        // fois le même dû.
        givenAs(admin).contentType("application/json")
                .body("{ \"delegateSupplierId\": \"%s\", \"amount\": 1000, \"paymentMethod\": \"BANK_TRANSFER\" }".formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(422);

        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 4000000, \"note\": \"Accord partiel\" }")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("data.status", equalTo("APPROVED"))
                .body("data.approvedAmount", equalTo(4000000));

        // C'est le montant approuvé qui commande : payer au-delà est
        // refusé, sinon l'approbation ne garantirait rien.
        pay(admin, delegateId, purchaseId, 5_000_000).statusCode(422);
        pay(admin, delegateId, purchaseId, 4_000_000).statusCode(201);

        givenAs(admin).when().get("/api/v1/settlement-requests/" + requestId)
                .then().statusCode(200)
                .body("data.status", equalTo("PAID"));

        // Soldée, elle ne décide plus rien : le solde restant redemande
        // une décision.
        pay(admin, delegateId, purchaseId, 1_000_000).statusCode(422);
    }

    @Test
    void a_refusal_carries_its_reason_and_stops_the_payment() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué Refusé");
        String purchaseId = unpaidReceipt(admin, delegateId, 2000, 1000);

        String requestId = givenAs(admin).contentType("application/json")
                .body("{ \"delegateSupplierId\": \"%s\", \"amount\": 2000000, \"paymentMethod\": \"BANK_TRANSFER\" }"
                        .formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .extract().path("data.id");

        // Un refus sans motif laisserait le demandeur sans réponse.
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/settlement-requests/" + requestId + "/reject")
                .then().statusCode(422);

        givenAs(admin).contentType("application/json")
                .body("{ \"note\": \"Compte courant débiteur, à revoir après livraison\" }")
                .when().post("/api/v1/settlement-requests/" + requestId + "/reject")
                .then().statusCode(200)
                .body("data.status", equalTo("REJECTED"))
                .body("data.decisionNote",
                        equalTo("Compte courant débiteur, à revoir après livraison"));

        pay(admin, delegateId, purchaseId, 2_000_000).statusCode(422);

        // Refusée, elle n'attend plus personne.
        givenAs(admin).queryParam("kind", "SETTLEMENT_REQUEST")
                .when().get("/api/v1/governance/approvals").then().statusCode(200)
                .body("data.page.items", org.hamcrest.Matchers.hasSize(0));
    }

    /** On n'accorde pas plus que ce qui est demandé : ce n'est pas une approbation. */
    @Test
    void an_approval_never_grants_more_than_was_asked() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué Plafond");

        String requestId = givenAs(admin).contentType("application/json")
                .body("{ \"delegateSupplierId\": \"%s\", \"amount\": 1000000, \"paymentMethod\": \"BANK_TRANSFER\" }"
                        .formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 1500000 }")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(422);
    }

    /**
     * Le second échelon, quand la structure l'a réglé : au-delà du seuil
     * de gouvernance, le droit ordinaire ne suffit plus.
     */
    @Test
    void above_the_governance_threshold_the_ordinary_right_is_not_enough() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0,"
                + "\"settlementGovernanceThreshold\":3000000}");
        String delegateId = delegate(admin, "Délégué Gouvernance");

        String requestId = givenAs(admin).contentType("application/json")
                .body("{ \"delegateSupplierId\": \"%s\", \"amount\": 4000000, \"paymentMethod\": \"BANK_TRANSFER\" }"
                        .formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .body("data.governanceApprovalRequired", equalTo(true))
                .extract().path("data.id");

        // La file dit que l'échelon est requis : celui qui n'a que le
        // droit ordinaire voit la demande sans pouvoir la trancher.
        givenAs(admin).queryParam("kind", "SETTLEMENT_REQUEST")
                .when().get("/api/v1/governance/approvals").then().statusCode(200)
                .body("data.page.items.governanceApprovalRequired", hasItem(true));

        assertThat(requestId).isNotNull();
    }

    /**
     * Le pouvoir de régler n'est pas le même selon l'instrument.
     *
     * <p>Réponse de l'expert-comptable le 13/09/2026 : un chèque engage
     * le compte en banque et remonte au président du conseil, une sortie
     * de caisse relève de la direction. Ce n'est pas une question de
     * montant : un petit chèque reste un chèque.</p>
     */
    @Test
    void the_means_of_payment_decides_which_tier_approves() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0,"
                + "\"settlementGovernanceMethods\":[\"CHEQUE\"]}");

        // Une grosse sortie de caisse, sans seuil de gouvernance :
        // la direction suffit.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "amount": 3000000,
                          "paymentMethod": "CASH" }
                        """.formatted(delegate(admin, "Délégué Caisse")))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .body("data.governanceApprovalRequired", equalTo(false));

        // Un chèque de rien du tout remonte tout de même au président.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "amount": 1000,
                          "paymentMethod": "CHEQUE" }
                        """.formatted(delegate(admin, "Délégué Chèque")))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .body("data.governanceApprovalRequired", equalTo(true))
                .body("data.paymentMethod", equalTo("CHEQUE"));
    }

    /**
     * Le moyen fait partie de ce qui est approuvé. Sans cette garde, on
     * ferait approuver une sortie de caisse par la direction puis on
     * paierait par chèque, ce que le président seul pouvait autoriser.
     */
    @Test
    void a_settlement_cannot_change_the_means_that_was_approved() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué Moyen");
        String purchaseId = unpaidReceipt(admin, delegateId, 2000, 1000);

        String requestId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "amount": 2000000,
                          "paymentMethod": "CHEQUE" }
                        """.formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // Le helper paie par virement : refusé, le chèque était accordé.
        // Les deux sortent pourtant du même compte en banque, et c'est
        // bien le propos : ce ne sont pas les mêmes instruments.
        pay(admin, delegateId, purchaseId, 2_000_000).statusCode(422);

        givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "delegateSupplierId": "%s", "paymentMethod": "CHEQUE",
                          "date": "%s", "allocations": [ { "purchaseId": "%s", "amount": %d } ] }
                        """.formatted(delegateId, LocalDate.now(), purchaseId, 2_000_000))
                .when().post("/api/v1/producer-payments").then().statusCode(201);
    }

    /**
     * La file « À payer » porte l'état de la demande sur sa ligne.
     *
     * <p>Signalé en production le 13/09/2026 : la caissière déposait une
     * demande, le directeur l'approuvait, et la ligne proposait toujours
     * « demander l'approbation ». L'écran allait chercher les demandes
     * par un second appel, soumis à un droit de lecture que la caisse ne
     * porte pas toujours ; son échec était silencieux, et le second dépôt
     * se faisait refuser sans que rien n'ait prévenu.</p>
     */
    @Test
    void the_payables_queue_carries_the_state_of_its_settlement_request() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0}");
        String delegateId = delegate(admin, "Délégué File");
        unpaidReceipt(admin, delegateId, 3000, 1000);

        // Rien de demandé : la ligne ne porte aucun état.
        givenAs(admin).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), nullValue());

        String requestId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "amount": 3000000,
                          "paymentMethod": "BANK_TRANSFER" }
                        """.formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), equalTo("PENDING_APPROVAL"));

        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // Approuvée, la ligne le dit : c'est ce qui manquait pour que
        // l'écran propose de payer au lieu de redemander.
        givenAs(admin).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), equalTo("APPROVED"))
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestId"
                        .formatted(delegateId), equalTo(requestId));
    }

    /**
     * Le parcours complet, avec trois personnes distinctes.
     *
     * <p>Déroulé par l'expert-comptable le 13/09/2026 : le comptable
     * comptabilise la livraison du délégué et le solde apparaît ; la
     * caissière demande l'approbation de le régler en espèces ; le
     * directeur et le président voient les montants ; la caissière
     * revient et paie.</p>
     *
     * <p>Chaque personne porte ses seuls droits. Un parcours joué par
     * l'administrateur, qui les a tous, ne prouve rien : c'est justement
     * un droit manquant chez la caissière qui avait fait disparaître
     * l'état de sa demande.</p>
     */
    @Test
    void the_whole_path_holds_with_three_people_and_their_own_rights() {
        UserEntity admin = tenantAdmin();
        setting(admin, "{\"settlementApprovalScope\":\"ALL\","
                + "\"settlementApprovalThreshold\":0,"
                + "\"settlementGovernanceMethods\":[\"CHEQUE\",\"BANK_TRANSFER\"]}");

        String delegateId = delegate(admin, "Délégué Parcours");
        String purchaseId = unpaidReceipt(admin, delegateId, 2000, 1000);

        UserEntity cashier = withProfile(admin, "caissiere", "Caissière",
                "\"TREASURY_WRITE\", \"COLLECTION_PAYMENT_WRITE\"");
        UserEntity director = withProfile(admin, "directeur", "Direction",
                "\"COLLECTION_SETTLEMENT_APPROVE\"");
        UserEntity chair = withProfile(admin, "president", "Président du conseil",
                "\"COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE\"");

        // 1. La caissière ouvre sa file et y voit le solde du délégué.
        givenAs(cashier).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), nullValue());

        // 2. Elle demande l'approbation d'un règlement en espèces.
        String requestId = givenAs(cashier).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "amount": 2000000,
                          "paymentMethod": "CASH", "notes": "Reliquat de campagne" }
                        """.formatted(delegateId))
                .when().post("/api/v1/settlement-requests").then().statusCode(201)
                // Les espèces ne remontent pas au conseil : la direction tranche.
                .body("data.governanceApprovalRequired", equalTo(false))
                .extract().path("data.id");

        // 3. Sa file le dit, sans qu'elle porte le droit de lire les
        //    demandes : c'est la ligne qui porte son état.
        givenAs(cashier).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), equalTo("PENDING_APPROVAL"));

        // 4. Le directeur et le président voient la demande dans la file
        //    des décisions, chacun avec son seul droit d'approbation.
        for (UserEntity who : java.util.List.of(director, chair)) {
            givenAs(who).queryParam("kind", "SETTLEMENT_REQUEST")
                    .when().get("/api/v1/governance/approvals").then().statusCode(200)
                    .body("data.page.items.find { it.sourceId == '%s' }.amount".formatted(requestId),
                            equalTo(2000000))
                    .body("data.page.items.find { it.sourceId == '%s' }.paymentMethod"
                            .formatted(requestId), equalTo("CASH"));
        }

        // 5. La caissière ne tranche pas ce qu'elle a demandé.
        givenAs(cashier).contentType("application/json").body("{}")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(403);

        // 6. Le directeur approuve.
        givenAs(director).contentType("application/json")
                .body("{ \"note\": \"Accord pour le reliquat\" }")
                .when().post("/api/v1/settlement-requests/" + requestId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        givenAs(cashier).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), equalTo("APPROVED"));

        // 7. Elle ne peut pas payer par un autre moyen que celui accordé.
        givenAs(cashier).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "delegateSupplierId": "%s", "paymentMethod": "BANK_TRANSFER",
                          "date": "%s", "allocations": [ { "purchaseId": "%s", "amount": %d } ] }
                        """.formatted(delegateId, LocalDate.now(), purchaseId, 2_000_000))
                .when().post("/api/v1/producer-payments").then().statusCode(422);

        // 8. Elle paie en espèces. Une caisse vide refuse, et le dit :
        //    approuver n'a jamais fait apparaître l'argent. C'est l'étape
        //    que le parcours de l'expert rencontrera en premier.
        String cashPayload = """
                { "delegateSupplierId": "%s", "paymentMethod": "CASH",
                  "date": "%s", "allocations": [ { "purchaseId": "%s", "amount": %d } ] }
                """.formatted(delegateId, LocalDate.now(), purchaseId, 2_000_000);
        givenAs(cashier).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body(cashPayload)
                .when().post("/api/v1/producer-payments").then().statusCode(422)
                .body("statusMessage", containsString("caisse"));

        // 9. La demande reste approuvée : un refus de caisse ne perd
        //    pas la décision, il attend que les fonds soient là.
        givenAs(admin).when().get("/api/v1/settlement-requests/" + requestId)
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        // 10. Et le solde reste dans la file : approuver n'est pas payer.
        givenAs(cashier).queryParam("kind", "DELEGATE_PURCHASE")
                .when().get("/api/v1/treasury/payables").then().statusCode(200)
                .body("data.page.items.find { it.beneficiaryId == '%s' }.settlementRequestStatus"
                        .formatted(delegateId), equalTo("APPROVED"));
    }

    /** Un compte qui ne porte qu'un profil, et ce profil que ces droits. */
    private UserEntity withProfile(UserEntity admin, String prefix, String roleName,
                                   String permissionsJson) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Parcours";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.USER);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);

        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }"
                        .formatted(roleName, permissionsJson))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id).then().statusCode(204);
        return u;
    }
}
