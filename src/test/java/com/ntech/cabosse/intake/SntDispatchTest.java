package com.ntech.cabosse.intake;

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
 * Répartir un extrait de traçabilité entre plusieurs bordereaux.
 *
 * <p>Un mois de retard fait des dizaines de bordereaux, et le fichier
 * national qui les couvre est unique : le charger bordereau par
 * bordereau obligeait à le découper autant de fois (demandé le
 * 04/10/2026).</p>
 *
 * <p>La règle vient du terrain : un bordereau vaut la somme de plusieurs
 * références, et ces références sont datées au plus tard du jour où le
 * camion est passé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SntDispatchTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate D1 = TODAY.minusDays(6);
    private static final LocalDate D2 = TODAY.minusDays(3);

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-disp-" + TestFixtures.randomSlugSuffix(), "Coopérative Répartition");
        // Le bordereau relève du négoce de matière première : sans le
        // modèle qui l'ouvre, ni campagne ni comptabilisation.
        tenant.organizationModel =
                com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Répartition";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 20_000_000);
        return u;
    }

    private record Fixture(String articleId, String siteId, String first, String second) {}

    /** Deux bordereaux d'un même délégué, à six et trois jours d'ici. */
    private Fixture twoNotes(UserEntity admin) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Principale 2026-2027", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 2800 }
                        """.formatted(TODAY.minusMonths(1), TODAY.plusMonths(4)))
                .when().post("/api/v1/campaigns").then().statusCode(201);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"KOUI IBOBE\",\"collector\":true,\"phone\":\"0172757084\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201);

        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0401", "date": "%s",
                            "supplierName": "KOUI IBOBE", "netWeightKg": "300" },
                          { "rowNumber": 3, "ref": "BR0402", "date": "%s",
                            "supplierName": "KOUI IBOBE", "netWeightKg": "200" } ]
                        """.formatted(D1, D2))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200).body("data.createdCount", equalTo(2));

        String first = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0401' }.id");
        String second = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0402' }.id");
        return new Fixture(articleId, siteId, first, second);
    }

    /** Le fichier du conseil : trois références, deux dates. */
    private String payload(Fixture f, String... extra) {
        String lines = """
                { "rowNumber": 2, "reference": "P-100-001", "date": "%s", "weightKg": "180",
                  "amount": "504000", "amountCash": "504000", "producerName": "KONE ADAMA",
                  "producerPhone": "0101010101",
                  "delegateName": "KOUI IBOBE", "delegatePhone": "0172757084" },
                { "rowNumber": 3, "reference": "P-100-002", "date": "%s", "weightKg": "120",
                  "amount": "336000", "amountCash": "336000", "producerName": "YAO AKISSI",
                  "producerPhone": "0202020202",
                  "delegateName": "KOUI IBOBE", "delegatePhone": "0172757084" },
                { "rowNumber": 4, "reference": "P-100-003", "date": "%s", "weightKg": "200",
                  "amount": "560000", "amountCash": "560000", "producerName": "DIALLO SEKOU",
                  "producerPhone": "0303030303",
                  "delegateName": "KOUI IBOBE", "delegatePhone": "0172757084" }
                """.formatted(D1, D1, D2);
        String tail = extra.length == 0 ? "" : ", " + String.join(", ", extra);
        return """
                { "articleId": "%s", "siteId": "%s",
                  "intakeIds": ["%s", "%s"], "lines": [ %s%s ] }
                """.formatted(f.articleId(), f.siteId(), f.first(), f.second(), lines, tail);
    }

    @Test
    void chaque_bordereau_prend_les_references_qui_le_precedent() {
        UserEntity admin = admin();
        Fixture f = twoNotes(admin);

        // Le plus ancien se remplit d'abord, jusqu'à son poids net :
        // 180 + 120 font les 300 kg du premier, les 200 du jour suivant
        // vont au second.
        givenAs(admin).contentType("application/json").body(payload(f))
                .when().post("/api/v1/intake-notes/accounting/dispatch/preview")
                .then().statusCode(200)
                .body("data.notes", hasSize(2))
                .body("data.notes[0].ref", equalTo("BR0401"))
                .body("data.notes[0].rowNumbers", hasSize(2))
                .body("data.notes[1].ref", equalTo("BR0402"))
                .body("data.notes[1].rowNumbers", hasSize(1))
                .body("data.unassigned", hasSize(0));
    }

    @Test
    void une_reference_posterieure_a_tous_les_bordereaux_reste_de_cote() {
        UserEntity admin = admin();
        Fixture f = twoNotes(admin);

        // Loger au plus proche créerait un reçu sur un camion qui était
        // déjà parti : la ligne ressort avec sa raison.
        String late = """
                { "rowNumber": 9, "reference": "P-100-009", "date": "%s", "weightKg": "50",
                  "amount": "140000", "amountCash": "140000", "producerName": "TARD VENU",
                  "producerPhone": "0909090909",
                  "delegateName": "KOUI IBOBE", "delegatePhone": "0172757084" }
                """.formatted(TODAY);

        givenAs(admin).contentType("application/json").body(payload(f, late))
                .when().post("/api/v1/intake-notes/accounting/dispatch/preview")
                .then().statusCode(200)
                .body("data.unassigned", hasSize(1))
                .body("data.unassigned[0].rowNumber", equalTo(9));
    }

    @Test
    void la_validation_comptabilise_chaque_bordereau_avec_ses_lignes() {
        UserEntity admin = admin();
        Fixture f = twoNotes(admin);

        givenAs(admin).contentType("application/json").body(payload(f))
                .when().post("/api/v1/intake-notes/accounting/dispatch/commit")
                .then().statusCode(200)
                .body("data.accountedNotes", equalTo(2))
                .body("data.failedNotes", equalTo(0))
                .body("data.createdReceipts", equalTo(3))
                .body("data.notes[0].accounted", equalTo(true))
                .body("data.notes[0].createdReceipts", equalTo(2));

        // Les deux bordereaux sont figés, chacun avec ses reçus.
        givenAs(admin).when().get("/api/v1/intake-notes/" + f.first())
                .then().statusCode(200)
                .body("data.status", equalTo("ACCOUNTED"))
                .body("data.receiptRefs", hasSize(2));
        givenAs(admin).when().get("/api/v1/intake-notes/" + f.second())
                .then().statusCode(200)
                .body("data.status", equalTo("ACCOUNTED"))
                .body("data.receiptRefs", hasSize(1));
    }

    @Test
    void un_bordereau_en_echec_n_annule_pas_les_autres() {
        UserEntity admin = admin();
        Fixture f = twoNotes(admin);

        // Le second est comptabilisé seul d'abord : la validation
        // groupée le retrouvera déjà figé.
        String alone = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 4, "reference": "P-100-003", "date": "%s", "weightKg": "200",
                    "amount": "560000", "amountCash": "560000", "producerName": "DIALLO SEKOU",
                    "producerPhone": "0303030303",
                    "delegateName": "KOUI IBOBE", "delegatePhone": "0172757084" } ] }
                """.formatted(f.articleId(), f.siteId(), D2);
        givenAs(admin).contentType("application/json").body(alone)
                .when().post("/api/v1/intake-notes/" + f.second() + "/accounting/commit")
                .then().statusCode(200);

        // Tout défaire pour celui-là obligerait à recharger le fichier
        // entier : le premier passe quand même.
        givenAs(admin).contentType("application/json").body(payload(f))
                .when().post("/api/v1/intake-notes/accounting/dispatch/commit")
                .then().statusCode(200)
                .body("data.accountedNotes", equalTo(1))
                .body("data.failedNotes", equalTo(1));

        givenAs(admin).when().get("/api/v1/intake-notes/" + f.first())
                .then().statusCode(200).body("data.status", equalTo("ACCOUNTED"));
    }
}
