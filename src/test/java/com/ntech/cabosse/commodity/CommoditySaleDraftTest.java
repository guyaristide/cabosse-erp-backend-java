package com.ntech.cabosse.commodity;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Le brouillon de vente en gros (VTE-28, demandé le 12/11/2026).
 *
 * <p>Une urgence interrompt la saisie d'une vente : on l'enregistre en
 * brouillon, incomplète, et on revient la valider plus tard, d'un autre
 * poste s'il le faut. Tant qu'elle n'est pas validée, rien n'est sorti,
 * rien n'est écrit, aucun numéro n'est pris, et le bordereau appelé
 * reste libre. La validation est une création ordinaire : refusée, elle
 * laisse le brouillon intact.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommoditySaleDraftTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private record Seed(String articleId, String customerId, String siteId, String noteId, String noteRef) {}

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-draft-" + TestFixtures.randomSlugSuffix(), "Coopérative Brouillon");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Brouillon";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 100_000_000);
        return u;
    }

    /** Un magasin, un reçu, un camion parti : un bordereau ouvert de 3 084 kg. */
    private Seed seed(UserEntity admin) {
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Méagui\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"s-"
                        + UUID.randomUUID().toString().substring(0, 8) + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"SUCDEN\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String receiptId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": 50, "weightKg": 3084,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");
        var note = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s", "truckNumber": "CI-3084-AB",
                          "lines": [{ "receiptId": "%s", "netKg": 3084, "bagsCount": 50 }] }
                        """.formatted(LocalDate.now(), siteId, customerId, receiptId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201).extract();
        return new Seed(articleId, customerId, siteId, note.path("data.id"), note.path("data.ref"));
    }

    private String noteStatus(UserEntity admin, Seed s) {
        return givenAs(admin).when().get("/api/v1/dispatch-notes/" + s.noteId())
                .then().statusCode(200).extract().path("data.status");
    }

    @Test
    void une_saisie_incomplete_se_garde_puis_se_valide_en_vente() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        // L'urgence arrive : le poids accepté n'est pas encore connu. Le
        // brouillon prend la saisie telle quelle.
        String draftId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteIds": ["%s"], "weights": {} }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId(), s.noteId()))
                .when().post("/api/v1/commodity/sales/drafts").then().statusCode(201)
                .body("data.customerName", equalTo("SUCDEN"))
                .body("data.articleName", equalTo("Fèves séchées"))
                .body("data.siteName", equalTo("Méagui"))
                .body("data.declaredKg", equalTo(3084))
                .body("data.acceptedKg", nullValue())
                .body("data.dispatchNoteRefs", hasSize(1))
                .body("data.payload.dispatchNoteIds[0]", equalTo(s.noteId()))
                .extract().path("data.id");

        // Rien n'a bougé : le bordereau reste ouvert, aucune vente n'existe.
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", noteStatus(admin, s));
        givenAs(admin).when().get("/api/v1/commodity/sales").then().statusCode(200)
                .body("data.total", equalTo(0));

        // La liste des brouillons le montre, sans sa saisie ; la fiche l'apporte.
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts").then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].id", equalTo(draftId))
                .body("data[0].payload", nullValue());
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(200)
                .body("data.payload.customerId", equalTo(s.customerId()))
                .body("data.payload.weights.acceptedKg", nullValue());

        // Valider sans poids accepté : refusé, et le brouillon reste.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteIds": ["%s"], "weights": {}, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId(), s.noteId()))
                .when().post("/api/v1/commodity/sales/drafts/" + draftId + "/validate")
                .then().statusCode(422);
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", noteStatus(admin, s));

        // Plus tard, calmement : le poids accepté est connu, la vente naît.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteIds": ["%s"], "weights": { "acceptedKg": 3000 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId(), s.noteId()))
                .when().post("/api/v1/commodity/sales/drafts/" + draftId + "/validate")
                .then().statusCode(201)
                .body("data.ref", startsWith("VC-"))
                .body("data.weights.declaredKg", equalTo(3084))
                .body("data.weights.acceptedKg", equalTo(3000))
                .body("data.movementRef", notNullValue());

        // Le brouillon a disparu, le bordereau est vendu, la vente est dans la liste.
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts").then().statusCode(200)
                .body("data", hasSize(0));
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(404);
        org.junit.jupiter.api.Assertions.assertEquals("SOLD", noteStatus(admin, s));
        givenAs(admin).when().get("/api/v1/commodity/sales").then().statusCode(200)
                .body("data.total", equalTo(1));
    }

    @Test
    void un_brouillon_se_reprend_et_se_supprime() {
        UserEntity admin = admin();
        Seed s = seed(admin);

        String draftId = givenAs(admin).contentType("application/json")
                .body("{ \"date\": \"%s\", \"customerId\": \"%s\" }".formatted(LocalDate.now(), s.customerId()))
                .when().post("/api/v1/commodity/sales/drafts").then().statusCode(201)
                .body("data.articleName", nullValue())
                .extract().path("data.id");

        // Repris : la saisie remplace la précédente, le résumé suit.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "weights": { "declaredKg": 1500, "acceptedKg": 1480 } }
                        """.formatted(LocalDate.now(), s.customerId(), s.articleId(), s.siteId()))
                .when().put("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(200)
                .body("data.id", equalTo(draftId))
                .body("data.articleName", equalTo("Fèves séchées"))
                .body("data.declaredKg", equalTo(1500))
                .body("data.acceptedKg", equalTo(1480));

        givenAs(admin).when().delete("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(204);
        givenAs(admin).when().delete("/api/v1/commodity/sales/drafts/" + draftId).then().statusCode(404);
        givenAs(admin).when().get("/api/v1/commodity/sales/drafts").then().statusCode(200)
                .body("data", hasSize(0));
    }
}
