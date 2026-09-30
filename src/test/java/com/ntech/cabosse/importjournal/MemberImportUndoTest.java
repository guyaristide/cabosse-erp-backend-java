package com.ntech.cabosse.importjournal;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Le journal d'un import, et son annulation (demandé le 30/09/2026).
 *
 * <p>Un import de quatre mille lignes qui en crée trente-quatre de moins
 * ne s'expliquait plus une fois la page fermée : le compte rendu vivait à
 * l'écran et nulle part ailleurs. Il fallait une capture, ou relancer le
 * fichier ailleurs pour reproduire.</p>
 *
 * <p>L'annulation part de ce que le journal a retenu, les identifiants
 * créés et eux seuls. Deviner à la date emporterait tout ce qui a été
 * saisi le même jour. Et un producteur qui a servi depuis n'est pas
 * supprimé : c'est le seul contrôle qui empêche de casser des faits que
 * l'import n'a pas créés.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportUndoTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-undo-" + TestFixtures.randomSlugSuffix(), "Coopérative Annulation");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Annulation";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 10_000_000);
        return u;
    }

    private void importTwo(UserEntity a) {
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "lastName": "KOUAME", "firstName": "Awa",
                            "gender": "FEMALE" },
                          { "rowNumber": 2, "lastName": "TRAORE", "firstName": "Issouf",
                            "gender": "MALE" } ]
                        """)
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200);
    }

    private String lastRunId(UserEntity a) {
        return givenAs(a).queryParam("domain", "members").when().get("/api/v1/import-runs")
                .then().statusCode(200)
                .body("data.total", greaterThanOrEqualTo(1))
                .extract().path("data.items[0].id");
    }

    @Test
    void le_journal_garde_ce_que_l_import_a_fait() {
        UserEntity a = admin();
        importTwo(a);

        // Le compte rendu survit à la page : c'est tout l'objet du journal.
        String runId = lastRunId(a);
        givenAs(a).when().get("/api/v1/import-runs/" + runId)
                .then().statusCode(200)
                .body("data.domain", equalTo("members"))
                .body("data.rowsReceived", equalTo(2))
                .body("data.rowsCreated", equalTo(2))
                // Et il dit lesquels : sans les identifiants, rien ne peut
                // être défait.
                .body("data.creations.kind", hasItem("member"));
    }

    @Test
    void l_annulation_defait_ce_qui_n_a_pas_servi() {
        UserEntity a = admin();
        importTwo(a);
        String runId = lastRunId(a);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/import-runs/" + runId + "/undo")
                .then().statusCode(200)
                .body("data.undone", greaterThanOrEqualTo(2))
                .body("data.kept", equalTo(0));

        givenAs(a).queryParam("perPage", 100).when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.items.name", not(hasItem("KOUAME Awa")))
                .body("data.items.name", not(hasItem("TRAORE Issouf")));
    }

    @Test
    void un_producteur_qui_a_livre_reste_en_place() {
        UserEntity a = admin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(a).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(a).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves\",\"unit\":\"kg\","
                        + "\"purchasable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        importTwo(a);
        String runId = lastRunId(a);
        String memberId = givenAs(a).queryParam("q", "KOUAME").when().get("/api/v1/members")
                .then().statusCode(200).extract().path("data.items[0].id");

        // Une livraison rattache ce producteur à un fait que l'import n'a
        // pas créé : le supprimer effacerait le reçu avec lui.
        givenAs(a).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/import-runs/" + runId + "/undo")
                .then().statusCode(200)
                // L'autre part, celui-ci reste, et le compte rendu dit
                // pourquoi : une annulation muette serait pire que rien.
                .body("data.kept", greaterThanOrEqualTo(1))
                .body("data.keptDetail.reason", hasItem(org.hamcrest.Matchers.containsString("livré")));

        givenAs(a).queryParam("q", "KOUAME").when().get("/api/v1/members")
                .then().statusCode(200).body("data.total", equalTo(1));
    }
}
