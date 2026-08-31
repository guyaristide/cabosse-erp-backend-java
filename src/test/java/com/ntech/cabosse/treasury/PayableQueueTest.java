package com.ntech.cabosse.treasury;

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

/**
 * La file de ce qui attend un décaissement.
 *
 * <p>Demandée par l'expert métier le 30/08/2026 : la trésorerie ne servait
 * qu'à déclarer des comptes et à organiser des transferts, alors que « tréso
 * c'est surtout décaisser et encaisser ». Personne ne pouvait répondre à
 * « combien la structure doit-elle sortir cette semaine, et à qui » : la
 * dette existait, éclatée dans quatre modules.</p>
 *
 * <p>Aucun état n'a été créé pour cela. Chaque source portait déjà
 * l'information qui dit qu'elle attend son paiement.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PayableQueueTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-tre-" + TestFixtures.randomSlugSuffix(), "Coopérative Trésorerie");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tréso";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 500_000_000);
        return u;
    }

    private String openCampaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKgFcfa": 900 }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private String delegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    /** Une avance déposée puis approuvée : engagée, pas encore sortie. */
    private String approvedAdvance(UserEntity who, String delegateId, String campaignId,
                                   long amount, LocalDate on) {
        String id = givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CHEQUE",
                          "campaignId": "%s" }
                        """.formatted(delegateId, on, amount, campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(who).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        return id;
    }

    private io.restassured.response.ValidatableResponse queue(UserEntity who, String query) {
        return givenAs(who).when().get("/api/v1/treasury/payables" + query).then().statusCode(200);
    }

    // ─── Ce que la file rassemble ───────────────────────────────────

    @Test
    void an_approved_advance_waits_in_the_queue() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String delegateId = delegate(admin, "Délégué Attente");
        approvedAdvance(admin, delegateId, campaign, 2_000_000, LocalDate.now());

        var response = queue(admin, "");
        List<String> kinds = response.extract().path("data.page.items.kind");
        assertThat(kinds).contains("COLLECTOR_ADVANCE");
        Number total = response.extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(2_000_000d);
    }

    @Test
    void a_disbursed_advance_leaves_the_queue() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String delegateId = delegate(admin, "Délégué Payé");
        String id = approvedAdvance(admin, delegateId, campaign, 1_500_000, LocalDate.now());

        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);

        // Les fonds sont sortis : la file n'a plus rien à dire dessus.
        // L'y laisser ferait payer deux fois.
        Number total = queue(admin, "").extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(0d);
    }

    @Test
    void a_request_awaiting_approval_is_not_in_the_queue() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String delegateId = delegate(admin, "Délégué Demande");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 900000, "paymentMethod": "CHEQUE",
                          "campaignId": "%s" }
                        """.formatted(delegateId, LocalDate.now(), campaign))
                .when().post("/api/v1/collector-advances").then().statusCode(201);

        // Une demande n'est pas une dette : elle peut encore être refusée.
        // La compter gonflerait le besoin de trésorerie d'un argent que
        // personne n'a décidé de sortir.
        Number total = queue(admin, "").extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(0d);
    }

    // ─── Le classement ──────────────────────────────────────────────

    @Test
    void the_oldest_comes_first() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String recent = delegate(admin, "Délégué Récent");
        String ancien = delegate(admin, "Délégué Ancien");
        approvedAdvance(admin, recent, campaign, 500_000, LocalDate.now().minusDays(2));
        approvedAdvance(admin, ancien, campaign, 500_000, LocalDate.now().minusDays(20));

        // Ce qui attend depuis le plus longtemps coûte le plus cher en
        // confiance, et c'est ce qu'un caissier cherche d'abord.
        List<String> names = queue(admin, "").extract().path("data.page.items.beneficiaryName");
        assertThat(names.get(0)).isEqualTo("Délégué Ancien");
    }

    @Test
    void the_age_is_counted_in_days() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        String delegateId = delegate(admin, "Délégué Âge");
        approvedAdvance(admin, delegateId, campaign, 400_000, LocalDate.now().minusDays(12));

        List<Integer> ages = queue(admin, "").extract().path("data.page.items.ageDays");
        assertThat(ages.get(0)).isEqualTo(12);
    }

    @Test
    void the_queue_says_how_long_the_oldest_has_waited() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        approvedAdvance(admin, delegate(admin, "GBAGBO Célestin"), campaign,
                400_000, LocalDate.now().minusDays(3));
        approvedAdvance(admin, delegate(admin, "ZADI Norbert"), campaign,
                400_000, LocalDate.now().minusDays(34));

        // Un total seul ne dit pas s'il y a du retard : cinq millions dus
        // depuis hier et cinq millions dus depuis six semaines n'appellent
        // pas la même décision.
        Number oldest = queue(admin, "").extract().path("data.oldestAgeDays");
        assertThat(oldest.longValue()).isEqualTo(34L);
    }

    @Test
    void an_empty_queue_has_no_age() {
        UserEntity admin = admin();

        // Zéro plutôt qu'une valeur héritée d'une file précédente : rien
        // n'attend, il n'y a pas d'ancienneté à annoncer.
        Number oldest = queue(admin, "").extract().path("data.oldestAgeDays");
        assertThat(oldest.longValue()).isEqualTo(0L);
    }

    // ─── Le total ───────────────────────────────────────────────────

    @Test
    void the_total_covers_everything_due_not_only_the_page() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        // Trois noms franchement distincts : la garde anti-doublons
        // rapproche les patronymes voisins, et « Délégué 1 » puis
        // « Délégué 2 » se seraient fait refuser comme un même homme.
        List<String> names = List.of("KONE Adama", "YAO Brou", "TRAORE Solange");
        for (int i = 0; i < names.size(); i++) {
            approvedAdvance(admin, delegate(admin, names.get(i)), campaign,
                    1_000_000, LocalDate.now().minusDays(i));
        }

        // Une page d'un seul élément, mais un total de trois : un caissier
        // qui lirait le total de la page croirait connaître son besoin.
        var response = queue(admin, "?perPage=1");
        assertThat((List<?>) response.extract().path("data.page.items")).hasSize(1);
        Number total = response.extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(3_000_000d);
        Number count = response.extract().path("data.beneficiaryCount");
        assertThat(count.intValue()).isEqualTo(3);
    }

    // ─── Les filtres ────────────────────────────────────────────────

    @Test
    void the_queue_narrows_to_one_kind() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        approvedAdvance(admin, delegate(admin, "Délégué Filtre"), campaign,
                700_000, LocalDate.now());

        Number total = queue(admin, "?kind=MEMBER_CREDIT")
                .extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(0d);

        Number advances = queue(admin, "?kind=COLLECTOR_ADVANCE")
                .extract().path("data.totalRemainingFcfa");
        assertThat(advances.doubleValue()).isEqualTo(700_000d);
    }

    // ─── Le symétrique : ce qu'on attend ────────────────────────────

    @Test
    void an_unsettled_sale_waits_in_the_receivables_queue() {
        UserEntity admin = admin();
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"s-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Chocolaterie de Paris\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"FINISHED_PRODUCT\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 10, "unitPriceFcfa": 2000 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=false").then().statusCode(201);

        // Une vente engagée et non réglée est une somme à recevoir, échue
        // ou non : c'est ce que le caissier prépare.
        var response = givenAs(admin).when().get("/api/v1/treasury/receivables")
                .then().statusCode(200);
        Number total = response.extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(20_000d);
        List<String> kinds = response.extract().path("data.page.items.kind");
        assertThat(kinds).containsExactly("SALE");
    }

    @Test
    void a_quote_is_not_a_receivable() {
        UserEntity admin = admin();
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"s-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Chocolaterie de Lyon\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"FINISHED_PRODUCT\",\"name\":\"Beurre de cacao\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 4, "unitPriceFcfa": 5000 } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId))
                .when().post("/api/v1/sales?asQuote=true").then().statusCode(201);

        // Un devis n'engage personne : le compter ferait attendre un
        // encaissement que rien ne fonde.
        Number total = givenAs(admin).when().get("/api/v1/treasury/receivables")
                .then().statusCode(200).extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(0d);
    }

    @Test
    void the_two_queues_never_mix() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin);
        approvedAdvance(admin, delegate(admin, "SANOGO Ibrahim"), campaign,
                1_200_000, LocalDate.now());

        // Une dette n'est pas une créance. Les mêler ferait croire que la
        // structure attend l'argent qu'elle doit sortir.
        Number owed = queue(admin, "").extract().path("data.totalRemainingFcfa");
        assertThat(owed.doubleValue()).isEqualTo(1_200_000d);
        Number expected = givenAs(admin).when().get("/api/v1/treasury/receivables")
                .then().statusCode(200).extract().path("data.totalRemainingFcfa");
        assertThat(expected.doubleValue()).isEqualTo(0d);
    }

    // ─── L'étanchéité entre structures ──────────────────────────────

    @Test
    void one_organization_never_sees_the_debt_of_another() {
        UserEntity first = admin();
        String campaign = openCampaign(first);
        approvedAdvance(first, delegate(first, "Délégué Chez Nous"), campaign,
                3_000_000, LocalDate.now());

        UserEntity other = admin();
        Number total = queue(other, "").extract().path("data.totalRemainingFcfa");
        assertThat(total.doubleValue()).isEqualTo(0d);
    }
}
