package com.ntech.cabosse.membercredit;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Crédits et avances aux producteurs membres.
 *
 * <p>Ce que la coopérative attend de ce mécanisme : qu'aucun fonds ne sorte
 * sans l'approbation de l'échelon que le montant impose, que le reste dû
 * soit lisible au moment où l'on paie, et que la retenue reste une décision
 * humaine plutôt qu'un prélèvement automatique.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberCreditTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-credit-" + TestFixtures.randomSlugSuffix(), "Coopérative Crédit");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user(Roles.TENANT_ADMIN, "admin");
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

    /** Compte USER doté d'un profil composé des droits donnés. */
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

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createCredit(UserEntity admin, String memberId, String kind, int amount, String purpose) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "%s", "amountFcfa": %d, "purpose": "%s" }
                        """.formatted(memberId, kind, amount, purpose))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
    }

    private void approveAndDisburse(UserEntity admin, String creditId) {
        givenAs(admin).contentType("application/json").body("{\"note\":\"Accord du conseil\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/approve")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CASH\",\"disbursedAt\":\"" + LocalDate.now() + "\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(200);
    }

    @Test
    void the_threshold_decides_which_approval_is_required() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{\"memberCreditApprovalThresholdFcfa\":500000}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        String memberId = createProducer(admin, "Kouassi");

        // Avance de carburant : la direction tranche seule.
        String small = createCredit(admin, memberId, "ADVANCE", 30000, "Carburant");
        givenAs(admin).when().get("/api/v1/member-credits/" + small)
                .then().statusCode(200)
                .body("data.governanceApprovalRequired", equalTo(false))
                .body("data.kind", equalTo("ADVANCE"));

        // Financement d'une moto : l'organe de gouvernance doit approuver.
        String large = createCredit(admin, memberId, "CREDIT", 750000, "Moto");
        givenAs(admin).when().get("/api/v1/member-credits/" + large)
                .then().statusCode(200)
                .body("data.governanceApprovalRequired", equalTo(true));
    }

    @Test
    void nothing_is_disbursed_before_approval() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Diabate");
        String creditId = createCredit(admin, memberId, "CREDIT", 200000, "Toiture");

        givenAs(admin).contentType("application/json")
                .body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(422)
                .body("statusMessage", containsString("approuvé"));

        // Et rien n'est imputable tant que les fonds ne sont pas sortis.
        givenAs(admin).contentType("application/json").body("{\"note\":\"OK\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        givenAs(admin).when().get("/api/v1/member-credits/members/" + memberId + "/debt")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(0));
    }

    @Test
    void the_retention_is_decided_delivery_by_delivery() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Yao");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        String creditId = createCredit(admin, memberId, "CREDIT", 150000, "Moto");
        approveAndDisburse(admin, creditId);

        // Le reste dû est lisible avant de payer.
        givenAs(admin).when().get("/api/v1/member-credits/members/" + memberId + "/debt")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(150000))
                .body("data.lines", hasSize(1));

        // Livraison de 500 kg à 1000 : 500 000 dus, dont 30 000 retenus,
        // exactement comme le président l'a décrit.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 500, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH",
                          "creditImputations": [ { "creditId": "%s", "amountFcfa": 30000 } ] }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, creditId))
                .when().post("/api/v1/producer-purchases")
                .then().statusCode(201)
                .body("data.creditImputedFcfa", equalTo(30000))
                .body("data.amountPaidFcfa", equalTo(470000))
                .body("data.remainderFcfa", equalTo(0));

        // La dette a baissé d'autant, et la retenue est tracée.
        givenAs(admin).when().get("/api/v1/member-credits/members/" + memberId + "/debt")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(120000));

        givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200)
                .body("data.imputations", hasSize(1))
                .body("data.imputations[0].amountFcfa", equalTo(30000))
                .body("data.imputedAmountFcfa", equalTo(30000));

        // L'écriture porte la contrepartie sur le compte de créance.
        givenAs(admin).when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.items[0].entries.syscohadaAccount", hasItem("409200"));
    }

    @Test
    void a_retention_larger_than_the_debt_is_refused() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Bamba");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        String creditId = createCredit(admin, memberId, "ADVANCE", 20000, "Entraide");
        approveAndDisburse(admin, creditId);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH",
                          "creditImputations": [ { "creditId": "%s", "amountFcfa": 50000 } ] }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, creditId))
                .when().post("/api/v1/producer-purchases")
                .then().statusCode(422)
                .body("statusMessage", containsString("reste dû"));

        // Rien n'a bougé : ni la dette, ni le stock.
        givenAs(admin).when().get("/api/v1/member-credits/members/" + memberId + "/debt")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(20000));
    }

    @Test
    void a_fully_repaid_credit_is_settled() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Sanogo");
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        String creditId = createCredit(admin, memberId, "ADVANCE", 25000, "Restauration");
        approveAndDisburse(admin, creditId);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH",
                          "creditImputations": [ { "creditId": "%s", "amountFcfa": 25000 } ] }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, creditId))
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200)
                .body("data.status", equalTo("SETTLED"))
                .body("data.remainingFcfa", equalTo(0));

        // Soldé, il ne pèse plus sur les livraisons suivantes.
        givenAs(admin).when().get("/api/v1/member-credits/members/" + memberId + "/debt")
                .then().statusCode(200)
                .body("data.totalRemainingFcfa", equalTo(0));
    }

    // ─── Séparation des tâches et seuil contraignant (CE-26, CE-27) ─

    private static final String[] CREDIT_RIGHTS = {
            "MEMBER_READ", "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE", "MEMBER_CREDIT_DISBURSE"};

    private String createCreditAs(UserEntity who, String memberId, int amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "CREDIT", "amountFcfa": %d, "purpose": "Intrants" }
                        """.formatted(memberId, amount))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void the_requester_cannot_approve_their_own_request() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Traore");
        UserEntity demandeur = operator(admin, "demandeur", CREDIT_RIGHTS);
        UserEntity collegue = operator(admin, "collegue", CREDIT_RIGHTS);

        String creditId = createCreditAs(demandeur, memberId, 100000);

        // Le demandeur ne tranche pas son propre dossier.
        givenAs(demandeur).contentType("application/json").body("{\"note\":\"OK\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/approve")
                .then().statusCode(422)
                .body("statusMessage", containsString("autre personne"));

        // Un collègue doté du droit, si.
        givenAs(collegue).contentType("application/json").body("{\"note\":\"Vu\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        // L'approbateur ne remet pas lui-même les fonds.
        givenAs(collegue).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(422)
                .body("statusMessage", containsString("autre personne"));

        // Le demandeur peut décaisser : deux paires d'yeux ont vu le dossier.
        givenAs(demandeur).contentType("application/json").body("{\"paymentMethod\":\"CASH\"}")
                .when().post("/api/v1/member-credits/" + creditId + "/disburse")
                .then().statusCode(200).body("data.status", equalTo("DISBURSED"));
    }

    @Test
    void the_governance_threshold_is_binding_not_decorative() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{\"memberCreditApprovalThresholdFcfa\":500000}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        String memberId = createProducer(admin, "Kone");

        UserEntity demandeur = operator(admin, "gestionnaire", CREDIT_RIGHTS);
        UserEntity direction = operator(admin, "direction", CREDIT_RIGHTS);
        UserEntity conseil = operator(admin, "conseil",
                "MEMBER_READ", "MEMBER_CREDIT_APPROVE", "MEMBER_CREDIT_APPROVE_GOVERNANCE");

        String large = createCreditAs(demandeur, memberId, 750000);

        // Le droit ordinaire ne suffit plus au-dessus du seuil.
        givenAs(direction).contentType("application/json").body("{\"note\":\"OK\"}")
                .when().post("/api/v1/member-credits/" + large + "/approve")
                .then().statusCode(403)
                .body("statusMessage", containsString("conseil"));

        // Le porteur du droit de gouvernance approuve.
        givenAs(conseil).contentType("application/json").body("{\"note\":\"Accord du conseil\"}")
                .when().post("/api/v1/member-credits/" + large + "/approve")
                .then().statusCode(200).body("data.status", equalTo("APPROVED"));

        // Sous le seuil, le droit ordinaire continue de suffire.
        String small = createCreditAs(demandeur, memberId, 100000);
        givenAs(direction).contentType("application/json").body("{\"note\":\"OK\"}")
                .when().post("/api/v1/member-credits/" + small + "/approve")
                .then().statusCode(200);
    }

    @Test
    void the_tenant_admin_remains_exempt_from_separation_of_duties() {
        // Une structure à compte unique doit rester opérable : l'admin
        // demande, approuve et décaisse, sous sa seule responsabilité.
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Ouattara");
        String creditId = createCredit(admin, memberId, "ADVANCE", 40000, "Carburant");
        approveAndDisburse(admin, creditId);
        givenAs(admin).when().get("/api/v1/member-credits/" + creditId)
                .then().statusCode(200).body("data.status", equalTo("DISBURSED"));
    }
}
