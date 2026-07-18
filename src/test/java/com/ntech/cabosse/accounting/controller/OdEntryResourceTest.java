package com.ntech.cabosse.accounting.controller;

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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Saisie manuelle d'OD (backlog CPT-07) : brouillon déséquilibré accepté,
 * validation refusée tant que déséquilibrée puis pièce au journal, et
 * contrôle de clôture « aucune OD en brouillard sur la période ».
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class OdEntryResourceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-od-" + TestFixtures.randomSlugSuffix(), "Coopérative OD");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tenant";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private String createDraft(UserEntity admin, LocalDate date, String body) {
        return givenAs(admin)
                .contentType("application/json")
                .body(body)
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void draft_can_be_unbalanced_but_validation_requires_balance() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();

        // Brouillon déséquilibré : accepté à la sauvegarde.
        String id = createDraft(admin, today, """
                { "date": "%s", "libelle": "Dotation amortissements",
                  "lines": [
                    { "account": "681", "libelle": "Dotation", "debitFcfa": 50000 },
                    { "account": "284", "libelle": "Amortissements cumulés", "creditFcfa": 40000 }
                  ] }
                """.formatted(today));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(422);

        // Correction puis validation : comptes 681/284 sont-ils au plan seedé ?
        // On utilise des comptes sûrs du plan (601 / 401) pour le test.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Régularisation",
                          "lines": [
                            { "account": "601", "libelle": "Charge", "debitFcfa": 50000 },
                            { "account": "401", "libelle": "Dette", "creditFcfa": 50000 }
                          ] }
                        """.formatted(today))
                .when().put("/api/v1/accounting/od/" + id)
                .then().statusCode(200)
                .body("data.balanced", equalTo(true));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200)
                .body("data.status", equalTo("VALIDATED"));

        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].sourceType", equalTo("MANUAL_ENTRY"));
    }

    @Test
    void locking_a_period_with_pending_od_is_rejected() {
        UserEntity admin = tenantAdmin();
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        createDraft(admin, lastMonth.atDay(15), """
                { "date": "%s", "libelle": "OD en attente",
                  "lines": [ { "account": "601", "libelle": "x", "debitFcfa": 1000 } ] }
                """.formatted(lastMonth.atDay(15)));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/lock")
                .then().statusCode(422);
    }

    @Test
    void documents_follow_draft_lifecycle() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String id = createDraft(admin, today, """
                { "date": "%s", "libelle": "Dotation avec justificatif",
                  "lines": [
                    { "account": "601", "libelle": "d", "debitFcfa": 1000 },
                    { "account": "401", "libelle": "c", "creditFcfa": 1000 }
                  ] }
                """.formatted(today));

        // Pièce jointe en brouillon : acceptée.
        String docId = givenAs(admin)
                .multiPart("label", "Tableau d'amortissement")
                .multiPart("file", "tableau.pdf", "%PDF-1.4 fake".getBytes(), "application/pdf")
                .when().post("/api/v1/accounting/od/" + id + "/documents")
                .then().statusCode(200)
                .body("data.documents", hasSize(1))
                .body("data.documents[0].label", equalTo("Tableau d'amortissement"))
                .extract().path("data.documents[0].id");

        givenAs(admin)
                .when().get("/api/v1/accounting/od/" + id + "/documents/" + docId)
                .then().statusCode(200)
                .header("Content-Type", equalTo("application/pdf"));

        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);

        // OD validée : lecture toujours possible, ajout et retrait refusés.
        givenAs(admin)
                .when().get("/api/v1/accounting/od/" + id + "/documents/" + docId)
                .then().statusCode(200);

        givenAs(admin)
                .multiPart("label", "Pièce tardive")
                .multiPart("file", "tard.pdf", "%PDF-1.4 fake".getBytes(), "application/pdf")
                .when().post("/api/v1/accounting/od/" + id + "/documents")
                .then().statusCode(422);

        givenAs(admin)
                .when().delete("/api/v1/accounting/od/" + id + "/documents/" + docId)
                .then().statusCode(422);
    }

    @Test
    void validated_draft_cannot_be_edited_or_deleted() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String id = createDraft(admin, today, """
                { "date": "%s", "libelle": "OD figée",
                  "lines": [
                    { "account": "601", "libelle": "d", "debitFcfa": 1000 },
                    { "account": "401", "libelle": "c", "creditFcfa": 1000 }
                  ] }
                """.formatted(today));
        givenAs(admin)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "modif", "lines": [] }
                        """.formatted(today))
                .when().put("/api/v1/accounting/od/" + id)
                .then().statusCode(422);

        givenAs(admin)
                .when().delete("/api/v1/accounting/od/" + id)
                .then().statusCode(422);
    }
}
