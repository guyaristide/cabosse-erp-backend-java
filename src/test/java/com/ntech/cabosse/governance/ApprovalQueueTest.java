package com.ntech.cabosse.governance;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * La file de ce qui attend une décision.
 *
 * <p>Celui qui approuve ne fait pas fonctionner la collecte : il tranche.
 * Le renvoyer vers la liste opérationnelle l'obligerait à filtrer
 * lui-même, au milieu de demandes déjà décaissées qui ne le concernent
 * plus.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ApprovalQueueTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-appr-" + TestFixtures.randomSlugSuffix(), "Coopérative Approbations");
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

    private String requestAdvance(UserEntity who, String delegateId, int amount, String note) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH", "notes": "%s" }
                        """.formatted(delegateId, LocalDate.now(), amount, note))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
    }

    // ─── Ce que la file rassemble ───────────────────────────────────

    @Test
    void it_gathers_what_awaits_a_decision_and_nothing_else() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Bakayoko");
        String pending = requestAdvance(admin, delegateId, 1_500_000, "Démarrage de section");
        String decided = requestAdvance(admin, delegateId, 400_000, "Carburant");
        givenAs(admin).when().post("/api/v1/collector-advances/" + decided + "/approve")
                .then().statusCode(200);

        // Une demande déjà tranchée ne concerne plus l'approbateur : la
        // laisser l'obligerait à filtrer lui-même.
        givenAs(admin).when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.requestCount", equalTo(1))
                .body("data.totalPending", equalTo(1500000))
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].sourceId", equalTo(pending));
    }

    @Test
    void it_carries_what_is_needed_to_decide() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Kouamé");
        requestAdvance(admin, delegateId, 900_000, "Avance de campagne");

        // Le document liste des colonnes qui ne sont pas décoratives : le
        // solde du délégué, pour voir si on a affaire à quelqu'un qui nous
        // doit, et le commentaire de l'émetteur.
        givenAs(admin).when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items[0].beneficiaryName", equalTo("Délégué Kouamé"))
                .body("data.page.items[0].requesterNote", equalTo("Avance de campagne"))
                .body("data.page.items[0].requestedByEmail", equalTo(admin.email))
                .body("data.page.items[0].accountBalance", notNullValue())
                .body("data.page.items[0].ageDays", equalTo(0));
    }

    @Test
    void the_oldest_request_comes_first() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Diallo");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 100000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201);
        String old = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 200000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now().minusDays(30)))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        // Comme les files de trésorerie : ce qui attend depuis le plus
        // longtemps coûte le plus cher en confiance.
        givenAs(admin).when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items[0].sourceId", equalTo(old))
                .body("data.oldestAgeDays", equalTo(30));
    }

    // ─── Deux files qui ne se valent pas ────────────────────────────

    @Test
    void the_producer_credits_are_consulted_never_decided_from_here() {
        UserEntity admin = tenantAdmin();
        String memberId = givenAs(admin).contentType("application/json")
                .body("{ \"lastName\": \"KOFFI\", \"firstName\": \"Yao\", "
                        + "\"gender\": \"MALE\", \"status\": \"ACTIVE\" }")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "CREDIT", "amount": 150000,
                          "requestedAt": "%s" }
                        """.formatted(memberId, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201);

        // « Le PCA n'a rien à faire ici, il consulte ce que le directeur a
        // fait. » Un bouton d'approbation contredirait le circuit à deux
        // mains.
        givenAs(admin).when().get("/api/v1/governance/approvals?kind=MEMBER_CREDIT")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].kind", equalTo("MEMBER_CREDIT"))
                .body("data.page.items[0].actionable", equalTo(false));
    }

    @Test
    void a_line_says_whether_the_reader_can_act_on_it() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"collectorAdvanceApprovalThreshold\": 1000000 }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        String delegateId = createDelegate(admin, "Délégué Sylla");
        UserEntity demandeur = operator(admin, "demandeur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");

        requestAdvance(demandeur, delegateId, 5_000_000, "Grosse avance");

        // Laisser croire qu'on peut trancher, puis refuser au clic, ferait
        // chercher un droit dont personne ne sait qu'il manque.
        givenAs(approbateur).when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items[0].governanceApprovalRequired", equalTo(true))
                .body("data.page.items[0].actionable", equalTo(false));
    }

    @Test
    void the_screen_stays_closed_to_whoever_approves_nothing() {
        UserEntity admin = tenantAdmin();
        UserEntity magasinier = operator(admin, "magasinier", "STOCK_READ");

        givenAs(magasinier).when().get("/api/v1/governance/approvals")
                .then().statusCode(403);
    }

    @Test
    void a_producer_request_shows_its_counterpart_to_the_reader() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 1200 }
                        """.formatted(LocalDate.now().minusDays(5), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201);
        String memberId = givenAs(admin).contentType("application/json")
                .body("{ \"lastName\": \"TANOH\", \"firstName\": \"Ama\", "
                        + "\"gender\": \"FEMALE\", \"status\": \"ACTIVE\" }")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        UserEntity agent = operator(admin, "agent", "MEMBER_READ", "MEMBER_CREDIT_REQUEST");
        givenAs(agent).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 150000,
                          "requestedAt": "%s" }
                        """.formatted(memberId, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201);

        // 150 000 au prix bord champ de 1 200 : les 125 kg du document.
        // Le conseil consulte, mais il consulte quelque chose de complet.
        givenAs(admin).when().get("/api/v1/governance/approvals?kind=MEMBER_CREDIT")
                .then().statusCode(200)
                .body("data.page.items[0].expectedQuantity", equalTo(125.0f))
                .body("data.page.items[0].expectedQuantityUnit", equalTo("kg"));
    }

    @Test
    void the_counterpart_travels_with_the_request() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"delegateMarginMode\": \"PER_KG\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        String campaignId = givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": 900 }
                        """.formatted(LocalDate.now().minusDays(5), LocalDate.now().plusDays(120)))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Délégué Tanoh\", \"collector\": true, "
                        + "\"collectorMarginRate\": 100 }")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 2000000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201);

        // Ce que le délégué doit ramener figure sous les yeux de celui qui
        // décide, dans l'unité de la donnée et sans nommer aucune filière.
        givenAs(admin).when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items[0].expectedQuantity", equalTo(2000.0f))
                .body("data.page.items[0].expectedQuantityUnit", equalTo("kg"));
    }

    @Test
    void a_credit_line_has_no_account_balance_but_carries_its_counterpart() {
        UserEntity admin = tenantAdmin();
        String memberId = givenAs(admin).contentType("application/json")
                .body("{ \"lastName\": \"BAMBA\", \"firstName\": \"Sita\", "
                        + "\"gender\": \"FEMALE\", \"status\": \"ACTIVE\" }")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 90000,
                          "requestedAt": "%s" }
                        """.formatted(memberId, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201);

        // Afficher zéro laisserait croire à un compte soldé, là où la
        // notion de compte courant n'existe pas pour cette nature.
        //
        // La contrepartie, elle, existe des deux côtés depuis le
        // 03/09/2026 : ici la campagne n'a pas de prix bord champ, donc
        // elle reste vide, mais le champ ne doit plus être forcé à vide.
        givenAs(admin).when().get("/api/v1/governance/approvals?kind=MEMBER_CREDIT")
                .then().statusCode(200)
                .body("data.page.items[0].accountBalance", nullValue());
    }
}
