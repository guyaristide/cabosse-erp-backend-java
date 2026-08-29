package com.ntech.cabosse.collector;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Le circuit d'une avance délégué : demander, approuver, décaisser.
 *
 * <p>Trois droits distincts, que la structure attribue aux profils qu'elle
 * veut. C'est la plus grosse sortie de trésorerie d'une campagne : elle ne
 * peut pas rester le geste d'une seule personne, alors qu'un crédit de
 * deux cent mille francs à un producteur passe déjà par trois mains.</p>
 *
 * <p>Ce que ces tests vérifient réellement : que l'argent ne sort qu'au
 * décaissement, qu'un droit manquant ferme la porte, et qu'une demande
 * refusée ne laisse rien derrière elle.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateAdvanceApprovalTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-circ-" + TestFixtures.randomSlugSuffix(), "Coopérative Circuit");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity admin = user(Roles.TENANT_ADMIN, "admin");
        // Une caisse ne peut jamais être négative : la structure y met
        // son solde d'ouverture avant toute sortie d'espèces.
        fundCashBox(admin, 50_000_000);
        return admin;
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Test";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    /** Compte USER doté d'un profil composé des seuls droits donnés. */
    private UserEntity operator(UserEntity admin, String prefix, String... permissions) {
        UserEntity u = user(Roles.USER, prefix);
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Profil %s\", \"permissions\": [%s] }".formatted(prefix, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id)
                .then().statusCode(204);
        return u;
    }

    private String createDelegate(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String requestAdvance(UserEntity who, String delegateId, int amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .extract().path("data.id");
    }

    private long journalCount(UserEntity admin) {
        return ((Number) givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200).extract().path("data.total")).longValue();
    }

    // ─── Les trois droits ───────────────────────────────────────────

    @Test
    void each_step_needs_its_own_permission() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Bamba");

        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");
        UserEntity caissier = operator(admin, "caissier",
                "COLLECTION_READ", "COLLECTION_ADVANCE_DISBURSE");

        String id = requestAdvance(demandeur, delegateId, 2_000_000);

        // Demander ne donne pas le droit de trancher.
        givenAs(demandeur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(403);
        // Ni celui de sortir les fonds.
        givenAs(demandeur).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(403);
        // Le caissier ne peut pas non plus approuver à la place du comité.
        givenAs(caissier).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(403);
        // Et l'approbateur ne peut pas déposer une demande.
        givenAs(approbateur).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 500000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(403);

        // Le décaissement ne vaut pas depuis une demande non approuvée.
        givenAs(caissier).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(422)
                .body("statusMessage", containsString("APPROVED"));

        givenAs(approbateur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200)
                .body("data.status", equalTo("APPROVED"))
                .body("data.approvedAt", notNullValue())
                .body("data.approvedByEmail", equalTo(approbateur.email));

        givenAs(caissier).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("OPEN"))
                .body("data.disbursedByEmail", equalTo(caissier.email))
                .body("data.pieceRef", notNullValue());
    }

    // ─── L'argent ne sort qu'au décaissement ────────────────────────

    @Test
    void nothing_leaves_the_treasury_before_the_disbursement() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Sanogo");
        // Une référence prise au départ : le journal porte déjà l'amorçage
        // de la caisse, et ce qu'on veut lire est ce que le circuit ajoute.
        long before = journalCount(admin);

        String id = requestAdvance(admin, delegateId, 3_000_000);
        // Une demande n'est pas un versement : rien au journal.
        org.assertj.core.api.Assertions.assertThat(journalCount(admin)).isEqualTo(before);

        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        // Approuver n'est pas payer non plus.
        org.assertj.core.api.Assertions.assertThat(journalCount(admin)).isEqualTo(before);

        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
        org.assertj.core.api.Assertions.assertThat(journalCount(admin)).isEqualTo(before + 1);
    }

    @Test
    void a_pending_advance_is_not_consumable_by_a_delivery() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Yao");
        String id = requestAdvance(admin, delegateId, 1_000_000);

        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"e-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kouame\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        // Le reçu passe : la matière entre, le producteur est payé. Mais
        // l'imputation ne trouve aucune avance ouverte à décompter, parce
        // que le délégué n'a encore rien reçu.
        givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH", "delegateSupplierId": "%s" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, delegateId))
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .body("data.consumedAmountFcfa", equalTo(0))
                .body("data.remainingFcfa", equalTo(1000000));
    }

    // ─── Le refus ───────────────────────────────────────────────────

    @Test
    void a_refusal_carries_its_reason_and_leaves_nothing_behind() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Diarra");
        long before = journalCount(admin);
        String id = requestAdvance(admin, delegateId, 5_000_000);

        // Un refus sans motif ne se conteste pas.
        givenAs(admin).contentType("application/json").body("{\"reason\":\"  \"}")
                .when().post("/api/v1/collector-advances/" + id + "/reject")
                .then().statusCode(422);

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Trésorerie insuffisante avant la traite\"}")
                .when().post("/api/v1/collector-advances/" + id + "/reject")
                .then().statusCode(200)
                .body("data.status", equalTo("REJECTED"))
                .body("data.rejectionReason", containsString("Trésorerie insuffisante"))
                .body("data.rejectedByEmail", equalTo(admin.email))
                .body("data.pieceRef", nullValue());

        org.assertj.core.api.Assertions.assertThat(journalCount(admin)).isEqualTo(before);

        // Un refus est terminal : il ne se décaisse pas, et il ne se clôt
        // pas non plus, ce qui laisserait croire à un décompte.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(422);
        givenAs(admin).contentType("application/json").body("{\"note\":\"Fin\"}")
                .when().post("/api/v1/collector-advances/" + id + "/close")
                .then().statusCode(422);
    }

    // ─── Séparation des tâches ──────────────────────────────────────

    private static final String[] ALL_THREE = {
            "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST",
            "COLLECTION_ADVANCE_APPROVE", "COLLECTION_ADVANCE_DISBURSE"};

    @Test
    void the_requester_cannot_approve_their_own_request() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Traoré");
        UserEntity demandeur = operator(admin, "demandeur", ALL_THREE);
        UserEntity collegue = operator(admin, "collegue", ALL_THREE);

        String id = requestAdvance(demandeur, delegateId, 1_500_000);

        // Détenir les trois droits ne dispense pas des deux paires d'yeux.
        givenAs(demandeur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(422)
                .body("statusMessage", containsString("autre personne"));

        givenAs(collegue).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        // L'approbateur ne sort pas lui-même les fonds.
        givenAs(collegue).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(422)
                .body("statusMessage", containsString("autre personne"));

        // Le demandeur, si : deux personnes ont vu le dossier.
        givenAs(demandeur).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200).body("data.status", equalTo("OPEN"));
    }

    @Test
    void a_single_account_structure_stays_operable() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Ouattara");
        String id = requestAdvance(admin, delegateId, 800_000);

        // L'administrateur du tenant est exempt de la séparation : une
        // structure à compte unique doit rester utilisable.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200).body("data.status", equalTo("OPEN"));
    }

    // ─── Les états ne se rejouent pas ───────────────────────────────

    @Test
    void a_decision_is_taken_only_once() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Coulibaly");
        long before = journalCount(admin);
        String id = requestAdvance(admin, delegateId, 600_000);

        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        // Réapprouver n'a pas de sens et ne doit pas passer en silence.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(422);
        // Refuser après approbation non plus.
        givenAs(admin).contentType("application/json").body("{\"reason\":\"Changement d'avis\"}")
                .when().post("/api/v1/collector-advances/" + id + "/reject")
                .then().statusCode(422);

        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
        // Un second décaissement sortirait deux fois les fonds.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(422);
        org.assertj.core.api.Assertions.assertThat(journalCount(admin)).isEqualTo(before + 1);
    }

    @Test
    void a_request_never_disbursed_does_not_inflate_the_delegate_account() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Zoumana");

        String verse = requestAdvance(admin, delegateId, 400_000);
        givenAs(admin).when().post("/api/v1/collector-advances/" + verse + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + verse + "/disburse")
                .then().statusCode(200);

        // Une demande en attente et une demande refusée, du même montant.
        requestAdvance(admin, delegateId, 900_000);
        String refuse = requestAdvance(admin, delegateId, 900_000);
        givenAs(admin).contentType("application/json").body("{\"reason\":\"Hors budget\"}")
                .when().post("/api/v1/collector-advances/" + refuse + "/reject")
                .then().statusCode(200);

        // Le compte courant ne connaît que l'argent réellement remis. Les
        // compter gonflerait son solde d'un argent qu'il n'a jamais eu.
        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalAdvancedFcfa", equalTo(400000))
                .body("data.advances", org.hamcrest.Matchers.hasSize(1));
    }
}
