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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Ce que la révision du document expert change, le 03/09/2026.
 *
 * <p>Trois choix de l'épic précédente sont amendés : la contrepartie se
 * saisit au lieu de se calculer, l'écriture porte le compte du
 * bénéficiaire au lieu d'un collectif, et les références changent de
 * masque.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceV2Test extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-v2-" + TestFixtures.randomSlugSuffix(), "Coopérative V2");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity a =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        a.code = "cacao-production";
        a.label = "Production de cacao";
        a.isPrimary = true;
        tenant.activities.add(a);
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

    private String campaign(UserEntity admin, int basePrice) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne 2026", "campaignYear": 2026,
                          "startDate": "%s", "endDate": "%s", "basePricePerKg": %d }
                        """.formatted(LocalDate.now().minusDays(5), LocalDate.now().plusDays(120),
                        basePrice))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
    }

    private String delegate(UserEntity admin, String name, String account) {
        givenAs(admin).contentType("application/json")
                .body("{ \"delegateMarginMode\": \"PER_KG\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        // Parenthèses obligatoires : « a + b.formatted(x) » n'applique le
        // format qu'à b, et les substitutions du premier littéral
        // survivent telles quelles dans la requête.
        String body = account == null
                ? "{ \"name\": \"%s\", \"collector\": true, \"collectorMarginRate\": 100 }"
                        .formatted(name)
                : ("{ \"name\": \"%s\", \"collector\": true, \"collectorMarginRate\": 100, "
                        + "\"advanceAccount\": \"%s\" }").formatted(name, account);
        return givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String member(UserEntity admin, String lastName, String account) {
        String body = account == null
                ? ("{ \"lastName\": \"%s\", \"firstName\": \"Yao\", \"gender\": \"MALE\", "
                        + "\"status\": \"ACTIVE\" }").formatted(lastName)
                : ("{ \"lastName\": \"%s\", \"firstName\": \"Yao\", \"gender\": \"MALE\", "
                        + "\"status\": \"ACTIVE\", \"advanceAccount\": \"%s\" }")
                        .formatted(lastName, account);
        return givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
    }

    // ─── CE-169 : la contrepartie se saisit ─────────────────────────

    @Test
    void the_scale_proposes_the_counterpart_when_nothing_is_typed() {
        UserEntity admin = tenantAdmin();
        String c = campaign(admin, 900);
        String d = delegate(admin, "Délégué Koffi", null);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 1000000, "paymentMethod": "CASH" }
                        """.formatted(d, LocalDate.now(), c))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        // 900 bord champ + 100 de marge = 1 000 le kilo, donc mille kilos.
        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.expectedQuantity", equalTo(1000.0f));
    }

    @Test
    void what_the_cooperative_types_is_what_is_kept() {
        UserEntity admin = tenantAdmin();
        String c = campaign(admin, 900);
        String d = delegate(admin, "Délégué Bamba", null);

        // « Laissé libre à la coop de saisir » : une contrepartie se
        // négocie et n'est pas toujours le quotient exact.
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s", "campaignId": "%s",
                          "advanceAmount": 1000000, "expectedQuantity": 850,
                          "paymentMethod": "CASH" }
                        """.formatted(d, LocalDate.now(), c))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().body("data.expectedQuantity", equalTo(850))
                // Le prix qui a servi de proposition reste au dossier :
                // sans lui, on ne saurait plus d'où venait le chiffre.
                .body("data.counterpartUnitPrice", equalTo(1000));
    }

    // ─── CE-170 : la contrepartie côté producteur ───────────────────

    @Test
    void the_producer_advance_carries_its_counterpart_too() {
        UserEntity admin = tenantAdmin();
        campaign(admin, 1200);
        String m = member(admin, "TRAORE", null);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 150000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");

        // Le prix bord champ, et non un barème délégué : le producteur
        // vend au prix de la campagne. 150 000 / 1 200 = 125 kg, le
        // chiffre exact du document.
        givenAs(admin).when().get("/api/v1/member-credits/" + id)
                .then().statusCode(200)
                .body("data.expectedQuantity", equalTo(125.0f))
                .body("data.expectedQuantityUnit", equalTo("kg"));
    }

    @Test
    void a_producer_advance_without_a_campaign_has_no_counterpart() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "BAMBA", null);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 90000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");

        // Zéro se lirait comme un engagement nul, ce qui est faux : il n'y
        // a pas de barème pour le calculer.
        givenAs(admin).when().get("/api/v1/member-credits/" + id)
                .then().body("data.expectedQuantity", nullValue());
    }

    // ─── CE-171 : le compte du bénéficiaire ─────────────────────────

    @Test
    void the_entry_debits_the_account_carried_by_the_delegate() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"409101\", \"label\": \"Avances délégué 1\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        String d = delegate(admin, "Délégué Sanogo", "409101");

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 1500000, "paymentMethod": "CASH" }
                        """.formatted(d, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);

        String pieceRef = givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().extract().path("data.pieceRef");
        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                .body("data.items[0].entries.find { it.debit > 0 }.syscohadaAccount",
                        equalTo("409101"));
    }

    @Test
    void a_delegate_without_an_account_falls_back_on_the_collective_one() {
        UserEntity admin = tenantAdmin();
        String d = delegate(admin, "Délégué Cissé", null);

        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 600000, "paymentMethod": "CASH" }
                        """.formatted(d, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);

        // Une avance ne se bloque pas parce qu'une fiche est incomplète.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.pieceRef", notNullValue());
    }

    @Test
    void an_account_absent_from_the_chart_is_refused() {
        UserEntity admin = tenantAdmin();

        // Le compte est ouvert par la coopérative dans son plan. Le
        // rattacher sans l'y trouver signalerait une faute de frappe qui
        // n'apparaîtrait qu'au premier décaissement, dans une écriture
        // pointant un compte inexistant.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Délégué Zoro\", \"collector\": true, "
                        + "\"advanceAccount\": \"409999\" }")
                .when().post("/api/v1/suppliers").then().statusCode(422);
    }

    @Test
    void two_parties_cannot_share_the_same_advance_account() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"409102\", \"label\": \"Avances délégué 2\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        delegate(admin, "Délégué Premier", "409102");

        // Deux tiers qui le partageraient rendraient le grand livre muet
        // sur ce que chacun doit, ce qui est la raison même d'ouvrir un
        // compte par tiers.
        givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Délégué Second\", \"collector\": true, "
                        + "\"advanceAccount\": \"409102\" }")
                .when().post("/api/v1/suppliers").then().statusCode(422);
    }

    @Test
    void the_producer_account_follows_the_same_two_rules() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"409202\", \"label\": \"Avances producteur 2\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        member(admin, "PREMIER", "409202");

        givenAs(admin).contentType("application/json")
                .body("{ \"lastName\": \"SECOND\", \"firstName\": \"Yao\", "
                        + "\"gender\": \"MALE\", \"status\": \"ACTIVE\", "
                        + "\"advanceAccount\": \"409202\" }")
                .when().post("/api/v1/members").then().statusCode(422);
        givenAs(admin).contentType("application/json")
                .body("{ \"lastName\": \"TROIS\", \"firstName\": \"Yao\", "
                        + "\"gender\": \"MALE\", \"status\": \"ACTIVE\", "
                        + "\"advanceAccount\": \"409888\" }")
                .when().post("/api/v1/members").then().statusCode(422);
    }

    // ─── L'approbation partielle, côté producteur aussi ─────────────

    @Test
    void the_director_may_grant_a_producer_less_than_asked() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "AKA", null);
        UserEntity directeur = operator(admin, "directeur",
                "MEMBER_READ", "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE");

        // Le document liste « Approbation Oui/Partiel » et « Montant
        // approuvé » des deux côtés, pas seulement chez le délégué.
        String id = givenAs(directeur).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 200000,
                          "approvedAmount": 150000, "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .body("data.status", equalTo("APPROVED"))
                .body("data.amount", equalTo(200000))
                .body("data.approvedAmount", equalTo(150000))
                .extract().path("data.id");

        // C'est le montant accordé qui forme la créance sur le producteur.
        givenAs(admin).when().get("/api/v1/member-credits/" + id)
                .then().body("data.remaining", equalTo(150000));
    }

    @Test
    void the_single_gesture_is_not_a_way_around_the_ceiling() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "ADJOUA", null);
        UserEntity directeur = operator(admin, "directeur",
                "MEMBER_READ", "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE");

        // Les mêmes bornes qu'à l'approbation ordinaire : accorder plus
        // que demandé au dépôt créerait un engagement que personne n'a
        // sollicité, par la voie la plus discrète.
        givenAs(directeur).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 100000,
                          "approvedAmount": 250000, "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(422);
    }

    @Test
    void a_producer_grant_above_the_request_is_refused() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "BROU", null);
        UserEntity agent = operator(admin, "agent", "MEMBER_READ", "MEMBER_CREDIT_REQUEST");
        UserEntity approbateur = operator(admin, "approbateur",
                "MEMBER_READ", "MEMBER_CREDIT_APPROVE");

        String id = givenAs(agent).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 100000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");

        givenAs(approbateur).contentType("application/json")
                .body("{ \"approvedAmount\": 150000 }")
                .when().post("/api/v1/member-credits/" + id + "/approve")
                .then().statusCode(422);
        givenAs(approbateur).contentType("application/json")
                .body("{ \"approvedAmount\": 0 }")
                .when().post("/api/v1/member-credits/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void the_producer_cash_goes_out_at_the_amount_granted() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "EHUI", null);
        UserEntity agent = operator(admin, "agent", "MEMBER_READ", "MEMBER_CREDIT_REQUEST");

        String id = givenAs(agent).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 300000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"approvedAmount\": 180000 }")
                .when().post("/api/v1/member-credits/" + id + "/approve").then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CASH\", \"disbursedAt\": \"%s\" }"
                        .formatted(LocalDate.now()))
                .when().post("/api/v1/member-credits/" + id + "/disburse").then().statusCode(200);

        // Le journal est la mémoire de ce qui est sorti : y porter le
        // montant demandé ferait mentir la balance.
        String pieceRef = givenAs(admin).when().get("/api/v1/member-credits/" + id)
                .then().extract().path("data.pieceRef");
        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                .body("data.items[0].totalDebit", equalTo(180000));
    }

    // ─── CE-175 : la numérotation ───────────────────────────────────

    @Test
    void a_delegate_request_is_numbered_DA_DEL() {
        UserEntity admin = tenantAdmin();
        String d = delegate(admin, "Délégué Yao", null);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 400000, "paymentMethod": "CASH" }
                        """.formatted(d, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .body("data.ref", startsWith("DA-DEL-"));
    }

    @Test
    void a_producer_request_is_numbered_DA_PRO() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "KOUASSI", null);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 80000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .body("data.ref", startsWith("DA-PRO-"));
    }

    // ─── CE-176 : le geste unique côté producteur ───────────────────

    @Test
    void the_director_grants_a_producer_advance_in_one_gesture() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "SORO", null);
        UserEntity directeur = operator(admin, "directeur",
                "MEMBER_READ", "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE");

        // « Le directeur valide sans attendre le PCA, car c'est lui seul
        // qui décide vu le montant. » Deux clics par la même personne ne
        // protègent de rien.
        givenAs(directeur).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 150000,
                          "requestedAt": "%s", "notes": "Il rapporte dans 3 jours" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .body("data.status", equalTo("APPROVED"))
                // L'approbation nomme son auteur : une décision qui ne
                // laisserait rien au dossier serait pire que deux clics.
                .body("data.approvedByEmail", equalTo(directeur.email))
                .body("data.approvedAt", notNullValue());
    }

    @Test
    void the_cash_desk_is_told_on_the_producer_side_too() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "GNAHORE", null);
        UserEntity caissier = operator(admin, "caissier",
                "MEMBER_READ", "MEMBER_CREDIT_DISBURSE");

        String ref = givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 120000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.ref");

        // « La caissière exécute en préparant les espèces sur la base des
        // avances validées qui lui parviennent en notifications. » Le
        // circuit producteur est plus court, sa troisième main est la même.
        java.util.List<String> targets = givenAs(admin).when()
                .get("/api/v1/notifications/journal?limit=100")
                .then().statusCode(200)
                .extract().path("data.findAll { it.subjectRef == '" + ref
                        + "' && it.eventType == 'member-credit.awaiting-disbursement' }.target");
        org.assertj.core.api.Assertions.assertThat(targets).contains(caissier.email);
    }

    @Test
    void whoever_cannot_approve_still_only_files_a_request() {
        UserEntity admin = tenantAdmin();
        String m = member(admin, "DIALLO", null);
        UserEntity agent = operator(admin, "agent", "MEMBER_READ", "MEMBER_CREDIT_REQUEST");

        // Le geste unique n'est pas une porte dérobée : il suppose le
        // droit d'approuver.
        givenAs(agent).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 120000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"));
    }

    @Test
    void above_the_threshold_the_single_gesture_no_longer_applies() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{ \"memberCreditApprovalThreshold\": 100000 }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        String m = member(admin, "OUATTARA", null);
        UserEntity directeur = operator(admin, "directeur",
                "MEMBER_READ", "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE");

        // La garde n'est levée que là où elle ne sert pas. Au-dessus du
        // seuil, la gouvernance se prononce et le circuit reste entier.
        givenAs(directeur).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "ADVANCE", "amount": 500000,
                          "requestedAt": "%s" }
                        """.formatted(m, LocalDate.now()))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .body("data.status", equalTo("PENDING_APPROVAL"))
                .body("data.governanceApprovalRequired", equalTo(true));
    }
}
