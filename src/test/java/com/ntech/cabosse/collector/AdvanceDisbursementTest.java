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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Ce que le décaissement d'une avance enregistre réellement.
 *
 * <p>Trois demandes de l'expert métier du 30/08/2026. Le chèque est un
 * mode de règlement à part entière, parce que les délégués n'ont pas de
 * compte en banque : on ne peut rien leur virer, on leur remet un chèque
 * qu'ils encaissent au guichet. Un virement coûte des frais, un chèque
 * n'en coûte pas, et ces frais sont à la charge de l'émetteur. Enfin
 * l'écran ne disait pas d'où sortait l'argent.</p>
 *
 * <p>Le compte, la référence et les frais se saisissent au décaissement,
 * pas à la demande : à la demande, personne ne sait encore de quel compte
 * l'argent sortira, ni quel numéro portera le chèque, ni ce que la banque
 * prélèvera.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceDisbursementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-dec-" + TestFixtures.randomSlugSuffix(), "Coopérative Décaissement");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Test";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        // Une caisse ne peut jamais être négative : la structure y met son
        // solde d'ouverture avant toute sortie d'espèces.
        fundCashBox(u, 50_000_000);
        return u;
    }

    private String createDelegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String requestAdvance(UserEntity who, String delegateId, int amount, String method) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "%s" }
                        """.formatted(delegateId, LocalDate.now(), amount, method))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
    }

    private void approve(UserEntity who, String id) {
        givenAs(who).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
    }

    /** Les lignes de la pièce comptable produite par le décaissement. */
    private List<Map<String, Object>> entriesOf(UserEntity who, String pieceRef) {
        List<Map<String, Object>> pieces = givenAs(who)
                .when().get("/api/v1/accounting/journal?perPage=100")
                .then().statusCode(200).extract().path("data.items");
        Map<String, Object> piece = pieces.stream()
                .filter(p -> pieceRef.equals(p.get("ref")))
                .findFirst().orElseThrow(() -> new AssertionError("pièce " + pieceRef + " absente"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) piece.get("entries");
        return entries;
    }

    private static double sumOn(List<Map<String, Object>> entries, String account, String side) {
        return entries.stream()
                .filter(e -> account.equals(e.get("syscohadaAccount")))
                .map(e -> e.get(side))
                .filter(java.util.Objects::nonNull)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .sum();
    }

    @Test
    void the_means_chosen_at_disbursement_decides_where_the_money_leaves_from() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Moyen Changé");
        // Prévu en espèces à la demande...
        String id = requestAdvance(admin, delegateId, 200_000, "CASH");
        approve(admin, id);

        // ...réglé par chèque le jour venu. C'est le moyen du décaissement
        // qui compte : le chèque se constate en banque, les espèces en
        // caisse, et l'intention de la demande n'engage rien.
        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"paymentMethod\": \"CHEQUE\" }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.paymentMethod", equalTo("CHEQUE"))
                .extract().path("data.pieceRef");

        List<Map<String, Object>> entries = entriesOf(admin, pieceRef);
        assertThat(sumOn(entries, "521000", "credit")).isEqualTo(200_000d);
        assertThat(sumOn(entries, "571000", "credit")).isZero();
    }

    @Test
    void a_cheque_is_accepted_and_leaves_the_bank() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Chèque");
        String id = requestAdvance(admin, delegateId, 1_000_000, "CHEQUE");
        approve(admin, id);

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"paymentRef\": \"CHQ-4417829\" }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("OPEN"))
                .body("data.paymentRef", equalTo("CHQ-4417829"))
                .extract().path("data.pieceRef");

        // Le chèque sort de la banque, pas de la caisse : c'est ce qui le
        // distingue des espèces, et ce qu'il partage avec le virement.
        List<Map<String, Object>> entries = entriesOf(admin, pieceRef);
        assertThat(sumOn(entries, "521000", "credit")).isEqualTo(1_000_000d);
        assertThat(sumOn(entries, "571000", "credit")).isZero();
    }

    @Test
    void bank_fees_are_a_charge_of_the_cooperative_not_of_the_delegate() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Virement");
        String id = requestAdvance(admin, delegateId, 1_000_000, "BANK_TRANSFER");
        approve(admin, id);

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"paymentRef\": \"VIR-990\", \"bankFees\": 5000 }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.bankFees", notNullValue())
                .extract().path("data.pieceRef");

        List<Map<String, Object>> entries = entriesOf(admin, pieceRef);

        // Les frais sont supportés par l'émetteur : le compte d'avance du
        // délégué ne porte que l'avance. S'ils s'y ajoutaient, il devrait
        // 1 005 000 à la clôture pour 1 000 000 reçus.
        assertThat(sumOn(entries, "409100", "debit")).isEqualTo(1_000_000d);
        assertThat(sumOn(entries, "631000", "debit")).isEqualTo(5_000d);
        // La banque est bien sortie de 1 005 000 au total.
        assertThat(sumOn(entries, "521000", "credit")).isEqualTo(1_005_000d);
    }

    @Test
    void the_fees_are_posted_as_their_own_line_on_the_bank() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Rapprochement");
        String id = requestAdvance(admin, delegateId, 1_000_000, "BANK_TRANSFER");
        approve(admin, id);

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"bankFees\": 5000 }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200).extract().path("data.pieceRef");

        // Deux crédits distincts sur la banque, et non un seul de
        // 1 005 000 : le rapprochement compare ligne par ligne sur le
        // compte de banque, pas le total de la pièce. Fondus, ni le
        // virement ni les frais ne se rapprocheraient de leur ligne de
        // relevé, et la ligne de frais partirait en régularisation contre
        // le 631, comptant la charge une seconde fois.
        List<Map<String, Object>> bankLines = entriesOf(admin, pieceRef).stream()
                .filter(e -> "521000".equals(e.get("syscohadaAccount")) && e.get("credit") != null)
                .toList();
        assertThat(bankLines).hasSize(2);
        assertThat(bankLines.stream()
                .map(e -> ((Number) e.get("credit")).doubleValue()).toList())
                .containsExactlyInAnyOrder(1_000_000d, 5_000d);
    }

    @Test
    void no_fees_means_no_fee_line_at_all() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Sans Frais");
        String id = requestAdvance(admin, delegateId, 1_000_000, "CHEQUE");
        approve(admin, id);

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"bankFees\": 0 }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                // Zéro et « pas de frais » se valent : un état ne doit pas
                // montrer des lignes à 0 FCFA.
                .body("data.bankFees", org.hamcrest.Matchers.nullValue())
                .extract().path("data.pieceRef");

        assertThat(sumOn(entriesOf(admin, pieceRef), "631000", "debit")).isZero();
    }

    @Test
    void the_disbursement_says_which_account_moved() {
        UserEntity admin = admin();
        // Un second compte de banque, sous son propre sous-compte : c'est
        // le cas que l'écran ne savait pas exprimer, et qui rendait la
        // phrase « tout va du 521000 » vraie par accident.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "number": "521100", "label": "Banque collecte" }
                        """)
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        String accountId = givenAs(admin).contentType("application/json")
                .body("""
                        { "bankName": "Banque de collecte", "label": "Compte collecte",
                          "kind": "BANQUE", "syscohadaAccount": "521100",
                          "accountNumber": "CI0012345" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");

        String delegateId = createDelegate(admin, "Délégué Sous-compte");
        String id = requestAdvance(admin, delegateId, 400_000, "CHEQUE");
        approve(admin, id);

        String pieceRef = givenAs(admin).contentType("application/json")
                .body("{ \"bankAccountId\": \"%s\" }".formatted(accountId))
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.bankAccountId", equalTo(accountId))
                .extract().path("data.pieceRef");

        List<Map<String, Object>> entries = entriesOf(admin, pieceRef);
        assertThat(sumOn(entries, "521100", "credit")).isEqualTo(400_000d);
        assertThat(sumOn(entries, "521000", "credit")).isZero();
    }

    @Test
    void disbursing_without_saying_anything_still_works() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Simple");
        String id = requestAdvance(admin, delegateId, 300_000, "CASH");
        approve(admin, id);

        // Une structure à une seule caisse n'a rien à préciser : le corps
        // reste facultatif, sans quoi on aurait alourdi le geste courant
        // pour servir un cas rare.
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200)
                .body("data.status", equalTo("OPEN"))
                .body("data.pieceRef", notNullValue());
        // Et avec un corps vide, qui est ce que l'écran enverra quand
        // l'opérateur n'a rien à préciser. Un autre délégué : un même
        // délégué doit apurer sa dette avant tout nouveau financement.
        String otherDelegate = createDelegate(admin, "Délégué Simple Bis");
        String other = requestAdvance(admin, otherDelegate, 100_000, "CASH");
        approve(admin, other);
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/collector-advances/" + other + "/disburse")
                .then().statusCode(200);
    }

    @Test
    void negative_fees_are_refused() {
        UserEntity admin = admin();
        String delegateId = createDelegate(admin, "Délégué Négatif");
        String id = requestAdvance(admin, delegateId, 300_000, "BANK_TRANSFER");
        approve(admin, id);

        givenAs(admin).contentType("application/json")
                .body("{ \"bankFees\": -1000 }")
                .when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(400);
    }
}
