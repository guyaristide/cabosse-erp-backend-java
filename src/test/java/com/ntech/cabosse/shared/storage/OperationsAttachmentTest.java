package com.ntech.cabosse.shared.storage;

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

/**
 * Les pièces qui suivent la matière (demandé le 22/10/2026).
 *
 * <p>Le magasinier joint le bordereau papier et la pesée au bordereau de
 * réception ; le comptable les lit sur le bordereau qu'il comptabilise ;
 * le chargement porte sa lettre de voiture, et la vente en gros son
 * décompte. Même mécanique partout : dépôt multipart, lecture, retrait.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class OperationsAttachmentTest extends AbstractIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4 bordereau".getBytes();

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-pj-" + TestFixtures.randomSlugSuffix(), "Coopérative Pièces");
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
        u.lastName = "Pièces";
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

    private String site(UserEntity admin) {
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    /** Dépôt, lecture puis retrait, sur n'importe quelle ressource qui expose des pièces. */
    private void roundTrip(UserEntity admin, String base) {
        String fileId = givenAs(admin)
                .multiPart("label", "Bordereau signé", "text/plain; charset=UTF-8")
                .multiPart("file", "bordereau.pdf", PDF, "application/pdf")
                .when().post(base + "/attachments")
                .then().statusCode(200)
                .body("data.attachments", hasSize(1))
                .body("data.attachments[0].label", equalTo("Bordereau signé"))
                .body("data.attachments[0].uploadedByEmail", equalTo(admin.email))
                .extract().path("data.attachments[0].fileId");

        givenAs(admin).when().get(base + "/attachments/" + fileId)
                .then().statusCode(200).contentType("application/pdf");

        givenAs(admin).when().get(base)
                .then().statusCode(200).body("data.attachments", hasSize(1));

        givenAs(admin).when().delete(base + "/attachments/" + fileId)
                .then().statusCode(200).body("data.attachments", hasSize(0));
    }

    @Test
    void un_bordereau_de_reception_porte_ses_pieces() {
        UserEntity admin = admin();
        String siteId = site(admin);
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "ref": "0922", "date": "%s", "siteId": "%s",
                          "productLabel": "CACAO", "bagCount": 1, "grossWeightKg": 40, "netWeightKg": 39 }
                        """.formatted(LocalDate.now(), siteId))
                .when().post("/api/v1/intake-notes").then().statusCode(201)
                .extract().path("data.id");
        roundTrip(admin, "/api/v1/intake-notes/" + id);
    }

    @Test
    void un_chargement_et_sa_vente_portent_les_leurs() {
        UserEntity admin = admin();
        String siteId = site(admin);
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\",\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\"}")
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
        String noteId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "siteId": "%s", "customerId": "%s",
                          "truckNumber": "CI-4521-AB",
                          "lines": [{ "receiptId": "%s", "netKg": 3084, "bagsCount": 50 }] }
                        """.formatted(LocalDate.now(), siteId, customerId, receiptId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/dispatch-notes").then().statusCode(201)
                .extract().path("data.id");
        roundTrip(admin, "/api/v1/dispatch-notes/" + noteId);

        String saleId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "dispatchNoteId": "%s",
                          "weights": { "acceptedKg": 3000 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), customerId, articleId, siteId, noteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .extract().path("data.id");
        roundTrip(admin, "/api/v1/commodity/sales/" + saleId);
    }
}
