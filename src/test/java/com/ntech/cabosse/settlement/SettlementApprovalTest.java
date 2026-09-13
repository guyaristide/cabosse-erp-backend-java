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
}
