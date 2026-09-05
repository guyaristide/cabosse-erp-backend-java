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
 * Approuver un montant qui n'est pas celui demandé.
 *
 * <p>La gouvernance peut ne pas suivre entièrement : elle accorde deux
 * millions là où le directeur en demandait trois. Le montant accordé
 * n'est pas un chiffre d'affichage, c'est celui qui sort de la caisse,
 * qui passe au journal et que le délégué devra couvrir. Un montant
 * approuvé que le reste du logiciel ignorerait serait pire que son
 * absence.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PartialApprovalTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-part-" + TestFixtures.randomSlugSuffix(), "Coopérative Partielle");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity admin = user(Roles.TENANT_ADMIN, "admin");
        fundCashBox(admin, 50_000_000);
        return admin;
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Awa";
        u.lastName = "KONE";
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

    /**
     * Un délégué dont la marge s'exprime au kilo : sans elle, il n'y a pas
     * de prix barème, donc rien à diviser pour obtenir la contrepartie.
     */
    private String delegateWithMargin(UserEntity admin, String name, int marginPerKg) {
        givenAs(admin).contentType("application/json")
                .body("{ \"delegateMarginMode\": \"PER_KG\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "%s", "collector": true, "collectorMarginRate": %d }
                        """.formatted(name, marginPerKg))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String requestAdvance(UserEntity who, String delegateId, int amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
    }

    // ─── Le montant accordé ─────────────────────────────────────────

    @Test
    void the_governing_body_may_grant_less_than_was_asked() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Coulibaly");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");

        String id = requestAdvance(demandeur, delegateId, 3_000_000);

        givenAs(approbateur).contentType("application/json")
                .body("""
                        { "approvedAmount": 2000000,
                          "note": "Bon délégué, mais la caisse est courte ce mois-ci." }
                        """)
                .when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200)
                .body("data.status", equalTo("APPROVED"))
                .body("data.advanceAmount", equalTo(3000000))
                .body("data.approvedAmount", equalTo(2000000))
                .body("data.approvalNote", containsString("caisse est courte"));
    }

    @Test
    void the_amount_that_leaves_the_cash_box_is_the_one_granted() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Kouassi");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");
        UserEntity caissier = operator(admin, "caissier",
                "COLLECTION_READ", "COLLECTION_ADVANCE_DISBURSE");

        String id = requestAdvance(demandeur, delegateId, 3_000_000);
        givenAs(approbateur).contentType("application/json")
                .body("{ \"approvedAmount\": 2000000 }")
                .when().post("/api/v1/collector-advances/" + id + "/approve").then().statusCode(200);

        // Un montant approuvé décoratif serait pire que son absence :
        // l'écran afficherait un chiffre que le reste du logiciel ignore.
        givenAs(caissier).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("OPEN"))
                .body("data.remaining", equalTo(2000000));

        // Et le compte courant du délégué ne doit pas lui réclamer le
        // montant qu'il a demandé, mais celui qu'il a reçu.
        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.advances.find { it.status == 'OPEN' }.amount", equalTo(2000000));
    }

    @Test
    void the_journal_carries_the_amount_granted() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Yao");
        String id = requestAdvance(admin, delegateId, 3_000_000);
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 1250000 }")
                .when().post("/api/v1/collector-advances/" + id + "/approve").then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);

        // Le journal est la mémoire de ce qui est sorti. Y porter le
        // montant demandé ferait mentir la balance sur une avance réduite.
        String pieceRef = givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200).extract().path("data.pieceRef");
        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                .body("data.items[0].totalDebit", equalTo(1250000));
    }

    @Test
    void granting_more_than_asked_would_be_an_engagement_nobody_requested() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Traoré");
        String id = requestAdvance(admin, delegateId, 1_000_000);

        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 1500000 }")
                .when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void granting_zero_is_a_refusal_and_a_refusal_carries_its_reason() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Bamba");
        String id = requestAdvance(admin, delegateId, 1_000_000);

        // L'enregistrer comme une approbation à zéro laisserait au dossier
        // une décision sans raison, que personne ne pourrait contester.
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 0 }")
                .when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void approving_without_a_body_grants_the_whole_amount() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Diarra");
        String id = requestAdvance(admin, delegateId, 800_000);

        // Le cas courant reste un oui sans commentaire.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200)
                .body("data.approvedAmount", equalTo(800000))
                .body("data.approvalNote", nullValue());
    }

    // ─── Le seuil de gouvernance ────────────────────────────────────

    private void setGovernanceThreshold(UserEntity admin, int amount) {
        givenAs(admin).contentType("application/json")
                .body("{ \"collectorAdvanceApprovalThreshold\": %d }".formatted(amount))
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    @Test
    void below_the_threshold_the_director_decides_alone() {
        UserEntity admin = tenantAdmin();
        setGovernanceThreshold(admin, 2_000_000);
        String delegateId = createDelegate(admin, "Délégué Sanogo");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");

        String id = requestAdvance(demandeur, delegateId, 500_000);

        // Faire remonter au conseil une avance de carburant paralyserait
        // le terrain.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.governanceApprovalRequired", equalTo(false));
        givenAs(approbateur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
    }

    @Test
    void above_the_threshold_the_ordinary_approval_no_longer_suffices() {
        UserEntity admin = tenantAdmin();
        setGovernanceThreshold(admin, 2_000_000);
        String delegateId = createDelegate(admin, "Délégué Ouattara");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");
        UserEntity gouvernance = operator(admin, "gouvernance", "COLLECTION_READ",
                "COLLECTION_ADVANCE_APPROVE", "COLLECTION_ADVANCE_APPROVE_GOVERNANCE");

        String id = requestAdvance(demandeur, delegateId, 5_000_000);

        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.governanceApprovalRequired", equalTo(true));
        givenAs(approbateur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(403);
        givenAs(gouvernance).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
    }

    @Test
    void a_threshold_raised_later_does_not_release_a_pending_request() {
        UserEntity admin = tenantAdmin();
        setGovernanceThreshold(admin, 2_000_000);
        String delegateId = createDelegate(admin, "Délégué Fofana");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");

        String id = requestAdvance(demandeur, delegateId, 5_000_000);
        setGovernanceThreshold(admin, 100_000_000);

        // Le drapeau est figé à la demande : relever le seuil ensuite ne
        // doit pas dispenser d'approbation un dossier déjà déposé.
        givenAs(approbateur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(403);
    }

    @Test
    void a_threshold_at_zero_sends_everything_to_the_governing_body() {
        UserEntity admin = tenantAdmin();
        setGovernanceThreshold(admin, 0);
        String delegateId = createDelegate(admin, "Délégué Cissé");
        String id = requestAdvance(admin, delegateId, 50_000);

        // Le cas de SCOOPANAB, où chaque avance délégué passe par le
        // conseil. Écrire 0 est une décision, et elle dit « tout ».
        //
        // La première version confondait ce zéro avec l'absence de
        // réglage et retournait l'intention : la coopérative qui écrivait
        // 0 obtenait que rien ne remonte au conseil. Le test portait le
        // nom de l'exigence et affirmait le contraire.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.governanceApprovalRequired", equalTo(true));
    }

    @Test
    void a_threshold_never_set_leaves_management_deciding_alone() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Kamagate");
        String id = requestAdvance(admin, delegateId, 9_000_000);
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");

        // Aucun seuil réglé : la structure n'a rien décidé, et lui imposer
        // la gouvernance bloquerait toutes ses approbations, personne ne
        // portant encore ce droit.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.governanceApprovalRequired", equalTo(false));
        givenAs(approbateur).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
    }

    // ─── La contrepartie attendue ───────────────────────────────────

    @Test
    void the_request_carries_what_the_delegate_is_expected_to_bring_back() {
        UserEntity admin = tenantAdmin();
        String campaignId = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 900 }
                        """.formatted(LocalDate.now().minusDays(10), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");

        String delegateId = delegateWithMargin(admin, "Délégué Koffi", 100);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 1000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        // Un million au barème de 1 000 FCFA le kilo : mille kilos.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                .body("data.expectedQuantity", equalTo(1000.0f))
                .body("data.expectedQuantityUnit", equalTo("kg"))
                .body("data.counterpartUnitPrice", equalTo(1000));
    }

    @Test
    void a_partial_approval_does_not_rewrite_the_counterpart() {
        UserEntity admin = tenantAdmin();
        String campaignId = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 900 }
                        """.formatted(LocalDate.now().minusDays(10), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        String delegateId = delegateWithMargin(admin, "Délégué N'Guessan", 100);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 1000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 500000 }")
                .when().post("/api/v1/collector-advances/" + id + "/approve").then().statusCode(200);

        // Tranché au registre (DEC-29) : la contrepartie est une prévision
        // portée par la demande, pas un engagement recalculé à chaque
        // décision. Le point se fait au retour du délégué.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.expectedQuantity", equalTo(1000.0f));
    }

    @Test
    void a_request_without_a_campaign_has_no_scale_and_so_no_counterpart() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Zongo");
        String id = requestAdvance(admin, delegateId, 1_000_000);

        // Zéro se lirait comme un engagement nul, ce qui est faux : il n'y
        // a pas de barème pour le calculer.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                .body("data.expectedQuantity", nullValue())
                .body("data.expectedQuantityUnit", nullValue());
    }

    // ─── La trace du règlement ──────────────────────────────────────

    @Test
    void the_disbursement_names_who_executed_it() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Soro");
        String id = requestAdvance(admin, delegateId, 400_000);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);

        // Une adresse électronique ne nomme personne dans un état remis au
        // conseil.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.disbursedByName", equalTo("Awa KONE"))
                .body("data.disbursedByEmail", notNullValue());
    }
}
