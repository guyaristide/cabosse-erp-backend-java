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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Bordereaux de réception (épic CE-218, DEC-41) : le constat du magasin
 * s'importe sans écrire le stock, puis la comptabilisation par l'extrait
 * de traçabilité nationale crée les reçus, reconnaît les producteurs par
 * téléphone puis nom, ouvre les absents au passage, et fige l'écart de
 * pesée. Une seconde validation est refusée.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class IntakeNoteTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-intake-" + TestFixtures.randomSlugSuffix(), "Coopérative Bordereaux");
        tenant.organizationModel =
                com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Bordereaux";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 5_000_000);
        return u;
    }

    @Test
    void the_note_is_a_statement_and_its_accounting_creates_the_receipts() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String campaignLabel = "Principale 2026-2027";
        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 2800 }
                        """.formatted(campaignLabel, today.minusMonths(1), today.plusMonths(4)))
                .when().post("/api/v1/campaigns").then().statusCode(201);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"KOUI IBOBE MARCELIN\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201);
        // Un producteur déjà connu, reconnu par son téléphone.
        givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"SORO ZANGA\",\"gender\":\"MALE\",\"status\":\"ACTIVE\","
                        + "\"phone\":\"+2250154536688\"}")
                .when().post("/api/v1/members").then().statusCode(201);

        // ─── Import du carnet : espaces insécables absorbées ───
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "campaignLabel": "%s", "productLabel": "Cacao",
                            "date": "%s", "movement": "Entrée Stock", "ref": "BR0254",
                            "truckNumber": "CJY1255", "supplierCode": "",
                            "supplierName": "KOUI\\u00A0IBOBE MARCELIN ",
                            "lineNumber": "1", "grossWeightKg": "1\\u00A0500",
                            "bagCount": "23", "netWeightKg": "1\\u00A0455" } ]
                        """.formatted(campaignLabel, today.format(
                                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1));
        String noteId = givenAs(admin).when().get("/api/v1/intake-notes?status=TO_ACCOUNT")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].ref", equalTo("BR0254"))
                .body("data[0].delegateSupplierId", org.hamcrest.Matchers.notNullValue())
                .extract().path("data[0].id");

        // Réimporter le même carnet ne double pas le bordereau.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0254", "date": "%s",
                            "netWeightKg": "1455" } ]
                        """.formatted(today))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.skippedExistingCount", equalTo(1));

        // ─── Prévisualisation : reconnu par téléphone, inconnu annoncé ───
        String lines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "reference": "P-453-873", "date": "%s",
                    "weightKg": "305", "amount": "854\\u00A0000", "amountCard": "0",
                    "amountCash": "854\\u00A0000", "producerName": "SORO ZANGA  ",
                    "producerPhone": "0154536688",
                    "delegateName": "KOUI IBOBE MARCELIN", "delegatePhone": "0172757084" },
                  { "rowNumber": 3, "reference": "P-816-654", "date": "%s",
                    "weightKg": "171", "amount": "478\\u00A0800", "amountCard": "0",
                    "amountCash": "478\\u00A0800", "producerName": "SIDIKI  FOFANA ",
                    "producerPhone": "0754756644",
                    "delegateName": "KOUI IBOBE MARCELIN", "delegatePhone": "0172757084" } ] }
                """.formatted(articleId, siteId, today, today);
        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/preview")
                .then().statusCode(200)
                .body("data.totalRows", equalTo(2))
                .body("data.delegateName", equalTo("KOUI IBOBE MARCELIN"))
                .body("data.delegateUnmatched", equalTo(false))
                .body("data.membersToCreate", equalTo(1))
                .body("data.rows[0].memberToCreate", equalTo(false))
                .body("data.rows[1].memberToCreate", equalTo(true));

        // ─── Validation : reçus créés, producteur ouvert, bordereau figé ───
        var commit = givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/commit")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(2))
                .body("data.createdMembers", equalTo(1))
                .extract().jsonPath();
        org.junit.jupiter.api.Assertions.assertEquals(0,
                new BigDecimal("476").compareTo(
                        new BigDecimal(commit.getString("data.totalWeightKg"))));
        // Écart de pesée : 1 455 net au bordereau, 476 comptabilisés.
        org.junit.jupiter.api.Assertions.assertEquals(0,
                new BigDecimal("979").compareTo(
                        new BigDecimal(commit.getString("data.weightGapKg"))));

        givenAs(admin).when().get("/api/v1/intake-notes/" + noteId)
                .then().statusCode(200)
                .body("data.status", equalTo("ACCOUNTED"))
                .body("data.receiptRefs", hasSize(2));

        // Une seconde validation est refusée : les reçus ne doublent pas.
        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/commit")
                .then().statusCode(422);

        // Comptabilisé, le bordereau ne se corrige ni ne se supprime :
        // ses reçus et son écart sont figés.
        givenAs(admin).contentType("application/json")
                .body("{ \"date\": \"%s\", \"netWeightKg\": 2555 }".formatted(today))
                .when().put("/api/v1/intake-notes/" + noteId)
                .then().statusCode(422);
        givenAs(admin).when().delete("/api/v1/intake-notes/" + noteId)
                .then().statusCode(422);

        // ─── Délégué nommé mais introuvable : la validation refuse ───
        // Un reçu créé sans rattachement n'apurerait jamais son compte
        // d'avances (constaté en production le 10/09/2026).
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0300", "date": "%s",
                            "supplierName": "BLE OULA LAURENT",
                            "netWeightKg": "500" } ]
                        """.formatted(today.format(
                                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200).body("data.createdCount", equalTo(1));
        String orphanId = givenAs(admin)
                .when().get("/api/v1/intake-notes?status=TO_ACCOUNT")
                .then().statusCode(200).extract().path("data[0].id");
        String orphanLines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "reference": "P-000-001", "date": "%s",
                    "weightKg": "100", "amount": "280000",
                    "producerName": "SORO ZANGA",
                    "delegateName": "BLE OULA LAURENT" } ] }
                """.formatted(articleId, siteId, today);
        givenAs(admin).contentType("application/json").body(orphanLines)
                .when().post("/api/v1/intake-notes/" + orphanId + "/accounting/preview")
                .then().statusCode(200)
                .body("data.delegateUnmatched", equalTo(true));
        givenAs(admin).contentType("application/json").body(orphanLines)
                .when().post("/api/v1/intake-notes/" + orphanId + "/accounting/commit")
                .then().statusCode(422);
        // Le bordereau reste à comptabiliser : rien n'a été consommé.
        givenAs(admin).when().get("/api/v1/intake-notes/" + orphanId)
                .then().statusCode(200)
                .body("data.status", equalTo("TO_ACCOUNT"));
    }

    @Test
    void a_commit_where_every_row_fails_reopens_the_note_and_says_why() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String frDate = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"SORO ZANGA\",\"gender\":\"MALE\",\"status\":\"ACTIVE\","
                        + "\"phone\":\"+2250154536699\"}")
                .when().post("/api/v1/members").then().statusCode(201);

        // Deux bordereaux, et le même extrait SNT rejoué sur les deux :
        // le cas constaté chez l'expert le 11/09/2026.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0261", "date": "%s", "netWeightKg": "305" },
                          { "rowNumber": 3, "ref": "BR0262", "date": "%s", "netWeightKg": "305" } ]
                        """.formatted(frDate, frDate))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200).body("data.createdCount", equalTo(2));
        String first = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0261' }.id");
        String second = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0262' }.id");

        String lines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "reference": "P-900-001", "date": "%s",
                    "weightKg": "305", "amount": "854000",
                    "producerName": "SORO ZANGA", "producerPhone": "0154536699" } ] }
                """.formatted(articleId, siteId, today);

        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + first + "/accounting/commit")
                .then().statusCode(200).body("data.createdReceipts", equalTo(1));

        // Le reçu officiel P-900-001 existe déjà : chaque ligne échoue.
        // « Comptabilisé sans aucun reçu » serait un mensonge : le
        // bordereau revient à comptabiliser et la raison remonte.
        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + second + "/accounting/commit")
                .then().statusCode(422);
        givenAs(admin).when().get("/api/v1/intake-notes/" + second)
                .then().statusCode(200)
                .body("data.status", equalTo("TO_ACCOUNT"));
    }

    /**
     * Le cas de production du 12/09/2026 : le système national de
     * traçabilité avait donné le même numéro de reçu à deux livraisons.
     * La seconde a été refusée, à juste titre, mais en silence : le
     * bordereau s'affichait comptabilisé et rien ne disait qu'il lui
     * manquait 1 505 kg. Il a fallu une journée pour le retrouver.
     */
    @Test
    void a_refused_row_is_kept_on_the_note_and_can_be_caught_up_later() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String frDate = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0271", "date": "%s", "netWeightKg": "500" },
                          { "rowNumber": 3, "ref": "BR0272", "date": "%s", "netWeightKg": "800" } ]
                        """.formatted(frDate, frDate))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200);
        String first = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0271' }.id");
        String second = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().extract().path("data.find { it.ref == 'BR0272' }.id");

        String shared = "P-777-001";
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "lines": [
                          { "rowNumber": 2, "reference": "%s", "date": "%s",
                            "weightKg": "500", "amount": "600000",
                            "producerName": "SORO ZANGA" } ] }
                        """.formatted(articleId, siteId, shared, today))
                .when().post("/api/v1/intake-notes/" + first + "/accounting/commit")
                .then().statusCode(200);

        // Le second bordereau porte deux lignes : l'une passe, l'autre
        // réemploie le numéro déjà pris par le premier.
        String withDuplicate = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "reference": "P-777-002", "date": "%s",
                    "weightKg": "300", "amount": "360000",
                    "producerName": "GLAHOU DAVID" },
                  { "rowNumber": 3, "reference": "%s", "date": "%s",
                    "weightKg": "500", "amount": "600000",
                    "producerName": "FIE KEI FRANCK JAURES" } ] }
                """.formatted(articleId, siteId, today, shared, today);
        givenAs(admin).contentType("application/json").body(withDuplicate)
                .when().post("/api/v1/intake-notes/" + second + "/accounting/commit")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(1))
                .body("data.skippedRows", equalTo(1));

        // La trace reste sur le bordereau : c'est elle qui manquait.
        givenAs(admin).when().get("/api/v1/intake-notes/" + second)
                .then().statusCode(200)
                .body("data.skippedRows.size()", equalTo(1))
                .body("data.skippedRows[0].reference", equalTo(shared))
                .body("data.skippedRows[0].producerName", equalTo("FIE KEI FRANCK JAURES"))
                .body("data.skippedRows[0].reason", org.hamcrest.Matchers.notNullValue())
                .body("data.accountedWeightKg", equalTo(300));

        // Le système national corrige son numéro : on réimporte, et seule
        // la ligne sans reçu est créée. Les autres restent en place.
        String corrected = withDuplicate.replace(shared, "P-777-003");
        givenAs(admin).contentType("application/json").body(corrected)
                .when().post("/api/v1/intake-notes/" + second + "/accounting/complete")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(1));

        givenAs(admin).when().get("/api/v1/intake-notes/" + second)
                .then().statusCode(200)
                .body("data.receiptRefs.size()", equalTo(2))
                .body("data.skippedRows.size()", equalTo(0))
                .body("data.accountedWeightKg", equalTo(800));

        // Plus rien à rattraper : le dire plutôt que de recréer en double.
        givenAs(admin).contentType("application/json").body(corrected)
                .when().post("/api/v1/intake-notes/" + second + "/accounting/complete")
                .then().statusCode(422);
    }

    @Test
    void the_keeper_corrects_or_deletes_a_note_before_accounting() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String frDate = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"MAG-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");

        // Le cas pratique de l'expert (11/09/2026) : 12 555 saisis au
        // lieu de 2 555 sur deux bordereaux.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BR0257", "date": "%s", "netWeightKg": "12555" },
                          { "rowNumber": 3, "ref": "BR0258", "date": "%s", "netWeightKg": "12555" } ]
                        """.formatted(frDate, frDate))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200).body("data.createdCount", equalTo(2));
        String noteId = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().statusCode(200)
                .extract().path("data.find { it.ref == 'BR0257' }.id");
        String otherId = givenAs(admin).when().get("/api/v1/intake-notes")
                .then().statusCode(200)
                .extract().path("data.find { it.ref == 'BR0258' }.id");

        // Correction d'un clic : le poids net redevient celui du carnet,
        // et le magasin d'entrée se règle ici, au magasin, pas à la
        // comptabilisation (11/09/2026).
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "supplierName": "KOUI IBOBE MARCELIN",
                          "siteId": "%s",
                          "grossWeightKg": 2594, "bagCount": 39, "netWeightKg": 2555 }
                        """.formatted(today, siteId))
                .when().put("/api/v1/intake-notes/" + noteId)
                .then().statusCode(200)
                .body("data.netWeightKg", equalTo(2555))
                .body("data.bagCount", equalTo(39))
                .body("data.siteId", equalTo(siteId));

        // Annulation de saisie : le constat s'efface, rien n'a bougé.
        givenAs(admin).when().delete("/api/v1/intake-notes/" + otherId)
                .then().statusCode(204);
        givenAs(admin).when().get("/api/v1/intake-notes")
                .then().statusCode(200).body("data", hasSize(1));
    }

    /**
     * Le magasinier saisit un bordereau devant son camion.
     *
     * <p>Le bordereau ne naissait que d'un import de carnet, ce qui
     * convient à une reprise d'historique et à rien d'autre : en
     * déroulant le cycle dans l'ordre réel le 13/09/2026, le magasinier
     * se retrouvait sans geste, et aucun écran ne disait pourquoi.</p>
     */
    @Test
    void the_keeper_enters_a_note_truck_by_truck() {
        UserEntity admin = admin();
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin saisie\",\"type\":\"TRANSFORMATION\",\"code\":\"MS-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"BABA OUEDRAOGO\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        String noteId = givenAs(admin).contentType("application/json")
                .body("""
                        { "ref": "BR0300", "date": "%s", "siteId": "%s",
                          "delegateSupplierId": "%s", "productLabel": "Cacao",
                          "truckNumber": "4521 CI", "grossWeightKg": 2594,
                          "bagCount": 39, "netWeightKg": 2555 }
                        """.formatted(LocalDate.now(), siteId, delegateId))
                .when().post("/api/v1/intake-notes").then().statusCode(201)
                // Le délégué désigné nomme le fournisseur : à l'import on
                // rapproche un libellé faute de mieux, ici on le connaît.
                .body("data.supplierName", equalTo("BABA OUEDRAOGO"))
                .body("data.netWeightKg", equalTo(2555))
                .body("data.status", equalTo("TO_ACCOUNT"))
                .extract().path("data.id");

        // Il rejoint la file du comptable, comme un bordereau importé.
        givenAs(admin).queryParam("status", "TO_ACCOUNT")
                .when().get("/api/v1/intake-notes").then().statusCode(200)
                .body("data.find { it.ref == 'BR0300' }.id", equalTo(noteId));

        // Le stock n'a pas bougé : la matière entre par les reçus
        // d'achat, à la comptabilisation, et par eux seuls. Rien n'a
        // encore été comptabilisé, donc aucun poids ne l'a été.
        givenAs(admin).when().get("/api/v1/intake-notes/" + noteId)
                .then().statusCode(200)
                .body("data.accountedWeightKg", nullValue());

        // Deux bordereaux du même numéro feraient entrer la matière deux
        // fois : le second est refusé.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "ref": "BR0300", "date": "%s", "netWeightKg": 1000 }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/intake-notes").then().statusCode(422);
    }

    /**
     * L'import laisse une trace consultable, et cette trace ne pèse sur
     * rien.
     *
     * <p>La revue du 15/09/2026 a montré que la plupart des anomalies
     * d'import ne sont pas des erreurs bruyantes mais des silences : une
     * ligne écartée sans compteur, un rattachement laissé nul. Rien ne
     * permettait ensuite de reconstituer ce qui s'était passé.</p>
     *
     * <p>Le test vérifie les deux moitiés : ce que la trace retient, et
     * qu'elle n'a rien changé à l'import lui-même.</p>
     */
    @Test
    void an_import_leaves_a_trace_without_changing_what_it_imported() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String frDate = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin trace\",\"type\":\"TRANSFORMATION\",\"code\":\"MT-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");

        // Deux lignes bonnes, une sans date, une sans poids. Le délégué
        // n'existe pas au référentiel : c'est une décision silencieuse.
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "ref": "BRT-1", "date": "%s", "netWeightKg": "2555",
                            "supplierName": "DELEGUE INCONNU" },
                          { "rowNumber": 3, "ref": "BRT-2", "date": "%s", "netWeightKg": "1200",
                            "supplierName": "DELEGUE INCONNU" },
                          { "rowNumber": 4, "ref": "BRT-3", "netWeightKg": "900" },
                          { "rowNumber": 5, "ref": "BRT-4", "date": "%s" } ]
                        """.formatted(frDate, frDate, frDate))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                // L'import se comporte exactement comme avant la trace.
                .body("data.createdCount", equalTo(2))
                .body("data.rejectedRows", hasSize(2));

        // Les deux bordereaux valides sont bien là : la trace n'a rien pris.
        givenAs(admin).when().get("/api/v1/intake-notes").then().statusCode(200)
                .body("data.find { it.ref == 'BRT-1' }.netWeightKg", equalTo(2555));

        UserEntity platform = fixtures.createPlatformAdmin();
        var run = givenAs(platform)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/import-runs")
                .then().statusCode(200)
                .body("data[0].domain", equalTo("intake-notes"))
                .body("data[0].rowsReceived", equalTo(4))
                .body("data[0].rowsCreated", equalTo(2))
                .body("data[0].rowsRejected", equalTo(2))
                .extract().path("data[0].id").toString();

        givenAs(platform)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/import-runs/" + run)
                .then().statusCode(200)
                // Les motifs groupés : c'est ce qui fait gagner du temps
                // quand quarante lignes tombent pour la même raison.
                .body("data.reasons.size()", equalTo(2))
                .body("data.rejections.rowNumber", hasItems(4, 5))
                // Et la décision que personne ne voyait : le bordereau
                // est créé sans délégué, donc il ne se comptabilisera pas.
                .body("data.decisions.find { it.field == 'fournisseur' }.occurrences",
                        equalTo(2));
    }
}
