package com.ntech.cabosse.diagnostics;

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
import static org.hamcrest.Matchers.hasItem;

/**
 * Le diagnostic de la plateforme (12/09/2026).
 *
 * <p>Une ligne d'un fichier de traçabilité n'avait pas produit de reçu,
 * et personne ne pouvait dire pourquoi : aucun écran ne montre ce qu'un
 * document porte réellement. Cet outil répond à la question, et à elle
 * seule : il lit, il ne corrige rien. Une donnée fautive se répare par
 * une migration.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantDiagnosticsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-diag-" + TestFixtures.randomSlugSuffix(), "Coopérative Diagnostic");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Diag";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    private String createReceipt(UserEntity admin, String officialRef) {
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"TRANSFORMATION\",\"code\":\"m-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"DYIZOUHOU\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        return givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 1505, "guaranteedPricePerKg": 1200,
                          "officialReceiptRef": "%s", "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, officialRef))
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.ref");
    }

    @Test
    void a_receipt_shows_what_it_really_carries_and_what_it_produced() {
        UserEntity admin = tenantAdmin();
        String officialRef = "P-" + TestFixtures.randomSlugSuffix();
        String ref = createReceipt(admin, officialRef);

        UserEntity platform = fixtures.createPlatformAdmin(
                "diag-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Agent", "Plateforme");

        var response = givenAs(platform).queryParam("q", officialRef)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/lookup")
                .then().statusCode(200);

        // Le document brut, tel que la base le porte : c'est le point de
        // l'outil, un DTO ne montrerait que ce qu'il a prévu de montrer.
        response.body("data.documents.collection", hasItem("producer_purchases"));
        List<String> refs = response.extract().path("data.documents.fields.ref");
        assertThat(refs).contains(ref);

        // Et ce que le reçu a produit derrière lui, d'un seul coup d'œil :
        // sans cela, il faut ouvrir trois écrans pour comprendre une panne.
        response.body("data.documents.relation", hasItem("mouvement de stock"));

        // Les montants se lisent comme des nombres, pas comme la structure
        // interne d'un Decimal128, et les identifiants comme des UUID.
        List<String> amounts = response.extract().path("data.documents.fields.amount");
        assertThat(amounts).contains("1806000");
    }

    @Test
    void the_consistency_report_names_its_checks() {
        UserEntity admin = tenantAdmin();
        createReceipt(admin, "P-" + TestFixtures.randomSlugSuffix());
        UserEntity platform = fixtures.createPlatformAdmin(
                "diag-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Agent", "Contrôles");

        var response = givenAs(platform)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/consistency")
                .then().statusCode(200);

        List<String> codes = response.extract().path("data.checks.code");
        assertThat(codes).contains("noteWeightMismatch", "receiptWithoutNote",
                "receiptWithoutCampaign", "receiptWithoutMovement", "advanceOverConsumed",
                "duplicateProducerName");

        // Un reçu passé par la création normale a bien fait entrer sa
        // matière : le contrôle ne doit pas crier au loup sur du sain.
        int index = codes.indexOf("receiptWithoutMovement");
        List<Integer> anomalies = response.extract().path("data.checks.anomalies");
        assertThat(anomalies.get(index)).isZero();
    }

    /**
     * Deux producteurs au même nom bloquent le rapprochement d'un
     * fichier de traçabilité : le contrôle les nomme, pour qu'on cesse
     * de chercher pourquoi une ligne saute à chaque livraison.
     */
    @Test
    void two_producers_sharing_a_name_are_reported() {
        UserEntity admin = tenantAdmin();
        for (int i = 0; i < 2; i++) {
            givenAs(admin).contentType("application/json")
                    .body("{\"lastName\":\"KOUAME\",\"firstName\":\"Yao\","
                            + "\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                    .when().post("/api/v1/members?force=true").then().statusCode(201);
        }
        UserEntity platform = fixtures.createPlatformAdmin(
                "diag-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Agent", "Doublons");

        var response = givenAs(platform)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/consistency")
                .then().statusCode(200);
        List<String> codes = response.extract().path("data.checks.code");
        List<Integer> anomalies = response.extract().path("data.checks.anomalies");
        assertThat(anomalies.get(codes.indexOf("duplicateProducerName"))).isPositive();
    }

    /** Un diagnostic se joint à un ticket : il se télécharge comme le reste. */
    @Test
    void both_diagnostics_download_as_files() {
        UserEntity admin = tenantAdmin();
        String officialRef = "P-" + TestFixtures.randomSlugSuffix();
        createReceipt(admin, officialRef);
        UserEntity platform = fixtures.createPlatformAdmin(
                "diag-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com",
                "Agent", "Export");

        String piece = givenAs(platform).queryParam("q", officialRef).queryParam("format", "csv")
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/lookup/export")
                .then().statusCode(200).extract().asString();
        assertThat(piece).contains("producer_purchases").contains(officialRef);

        String checks = givenAs(platform).queryParam("format", "csv")
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/consistency/export")
                .then().statusCode(200).extract().asString();
        // Un contrôle sans anomalie garde sa ligne, sinon le fichier
        // laisserait croire qu'il n'a pas été passé.
        assertThat(checks).contains("receiptWithoutMovement").contains("duplicateProducerName");
    }

    @Test
    void a_tenant_administrator_never_reaches_the_platform_diagnostics() {
        UserEntity admin = tenantAdmin();

        // L'outil montre des documents bruts : il n'a de sens que pour qui
        // dépanne le logiciel, et l'ouvrir au client serait une fuite.
        givenAs(admin).queryParam("q", "ACP")
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/lookup")
                .then().statusCode(403);
        givenAs(admin)
                .when().get("/api/v1/admin/diagnostics/" + tenant.id + "/consistency")
                .then().statusCode(403);
    }
}
