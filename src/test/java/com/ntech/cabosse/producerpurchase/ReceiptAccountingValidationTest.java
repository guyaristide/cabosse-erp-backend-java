package com.ntech.cabosse.producerpurchase;

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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * La bannette « Comptabiliser maintenant » (DEC-36, V2 de l'expert).
 *
 * <p>En mode MANUAL, la livraison attend le comptable : le stock et le
 * compte courant sont constatés à la réception, la pièce comptable seule
 * diffère, et c'est le clic du comptable qui la génère. Un reçu annulé
 * avant le clic n'a rien à contre-passer en comptabilité, et un reçu
 * comptabilisé ne se comptabilise pas deux fois.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ReceiptAccountingValidationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-bannette-" + TestFixtures.randomSlugSuffix(), "Coopérative Bannette");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
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
        fundCashBox(u, 50_000_000);
        // Le mode de l'expert : la livraison attend le comptable.
        givenAs(u).contentType("application/json")
                .body("{ \"receiptAccountingMode\": \"MANUAL\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return u;
    }

    private record Refs(String memberId, String articleId, String siteId) {}

    private Refs referentials(UserEntity admin) {
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Seka\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        return new Refs(memberId, articleId, siteId);
    }

    private String createReceipt(UserEntity admin, Refs refs) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 200, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(), refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .body("data.accountingStatus", equalTo("PENDING"))
                .body("data.pieceRef", nullValue())
                .body("data.movementRef", notNullValue())
                .extract().path("data.id");
    }

    @Test
    void the_accountant_click_generates_the_entries() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);
        String receiptId = createReceipt(admin, refs);

        // Le stock est constaté malgré l'attente : la matière est entrée.
        givenAs(admin).when()
                .get("/api/v1/stocks/" + refs.articleId() + "/sites/" + refs.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(200));

        // La livraison attend dans la bannette, la plus ancienne d'abord.
        // Le contenu, pas seulement le compte : un total juste avec une
        // page vide (saut de page mal calculé) a déjà été vu vivant.
        givenAs(admin).when().get("/api/v1/producer-purchases/pending-accounting")
                .then().statusCode(200)
                .body("data.total", greaterThanOrEqualTo(1))
                .body("data.items.find { it.id == '%s' }.accountingStatus".formatted(receiptId),
                        equalTo("PENDING"));

        // Le clic du comptable : la pièce part, l'achat au débit en 60x.
        String pieceRef = givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/producer-purchases/" + receiptId + "/post-accounting")
                .then().statusCode(200)
                .body("data.accountingStatus", equalTo("POSTED"))
                .body("data.pieceRef", notNullValue())
                .extract().path("data.pieceRef");
        givenAs(admin).when().get("/api/v1/accounting/journal?search=" + pieceRef)
                .then().statusCode(200)
                .body("data.items[0].entries.find { it.debit > 0 }.debit", equalTo(200000));

        // Deux fois, non : la pièce existe.
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/producer-purchases/" + receiptId + "/post-accounting")
                .then().statusCode(422);
    }

    @Test
    void a_pending_receipt_cancels_without_any_reversal_piece() {
        UserEntity admin = tenantAdmin();
        Refs refs = referentials(admin);
        String receiptId = createReceipt(admin, refs);

        // Annulé avant le clic : le stock est contre-passé, la comptabilité
        // n'a rien à défaire puisque rien n'était écrit.
        givenAs(admin).contentType("application/json")
                .body("{ \"reason\": \"Erreur de saisie du magasinier\" }")
                .when().post("/api/v1/producer-purchases/" + receiptId + "/cancel")
                .then().statusCode(200)
                .body("data.status", equalTo("CANCELLED"))
                .body("data.cancellation.reversalPieceRef", nullValue());

        // Il a quitté la bannette : un reçu annulé n'attend plus personne.
        givenAs(admin).when().get("/api/v1/producer-purchases/pending-accounting")
                .then().statusCode(200).body("data.total", equalTo(0));
    }
}
