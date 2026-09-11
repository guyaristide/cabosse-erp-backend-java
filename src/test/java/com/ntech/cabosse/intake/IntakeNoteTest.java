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
import static org.hamcrest.Matchers.hasSize;

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

        // Correction d'un clic : le poids net redevient celui du carnet.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "supplierName": "KOUI IBOBE MARCELIN",
                          "grossWeightKg": 2594, "bagCount": 39, "netWeightKg": 2555 }
                        """.formatted(today))
                .when().put("/api/v1/intake-notes/" + noteId)
                .then().statusCode(200)
                .body("data.netWeightKg", equalTo(2555))
                .body("data.bagCount", equalTo(39));

        // Annulation de saisie : le constat s'efface, rien n'a bougé.
        givenAs(admin).when().delete("/api/v1/intake-notes/" + otherId)
                .then().statusCode(204);
        givenAs(admin).when().get("/api/v1/intake-notes")
                .then().statusCode(200).body("data", hasSize(1));
    }
}
