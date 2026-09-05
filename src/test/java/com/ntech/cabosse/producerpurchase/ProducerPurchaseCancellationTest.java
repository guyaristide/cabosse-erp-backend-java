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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Contre-passation d'un reçu d'achat producteur.
 *
 * <p>Le reçu est la seule voie d'entrée matière d'une coopérative. Sur le
 * terrain, l'erreur de saisie est certaine : mauvais poids, mauvais
 * producteur, mauvais prix. Il n'existait pourtant ni modification, ni
 * suppression, ni annulation, et le retirer supposait une intervention en
 * base sur quatre collections.</p>
 *
 * <p>Ce qui compte ici : que l'annulation défasse vraiment tout. Le stock,
 * mais aussi le coût moyen — une sortie compensatoire retire la quantité
 * sans jamais toucher au CMUP, et en mode « par lot » le prix de
 * l'opération annulée resterait purement et simplement en place.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProducerPurchaseCancellationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-annul-" + TestFixtures.randomSlugSuffix(), "Coopérative Annulation");
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
        // Une caisse ne peut jamais être négative : la structure y met
        // son solde d'ouverture avant toute sortie d'espèces.
        fundCashBox(u, 50_000_000);
        return u;
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
    }

    private String createReceipt(UserEntity admin, String memberId, String articleId,
                                 String siteId, int weight, int price) {
        return givenAs(admin).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKg": %d,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId, weight, price))
                .when().post("/api/v1/producer-purchases")
                .then().statusCode(201).extract().path("data.id");
    }

    private void cancel(UserEntity admin, String receiptId, int expectedStatus) {
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Poids saisi par erreur, sac non pesé\"}")
                .when().post("/api/v1/producer-purchases/" + receiptId + "/cancel")
                .then().statusCode(expectedStatus);
    }

    private org.bson.Document stockItem(String articleId, String siteId) {
        return mongoClient.getDatabase(tenant.databaseName)
                .getCollection("stock_items")
                .find(new org.bson.Document("articleId", UUID.fromString(articleId))
                        .append("siteId", UUID.fromString(siteId)))
                .first();
    }

    /** Les montants sont en Decimal128 : on compare des nombres, pas leur écriture. */
    private static java.math.BigDecimal decimal(org.bson.Document doc, String field) {
        Object raw = doc.get(field);
        if (raw instanceof org.bson.types.Decimal128 d) return d.bigDecimalValue();
        return new java.math.BigDecimal(raw.toString());
    }

    @Test
    void cancelling_restores_the_quantity_and_the_average_cost() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String producer = createProducer(admin, "Kouassi");

        // Une première entrée à 1 000 F/kg fixe le coût moyen.
        createReceipt(admin, producer, articleId, siteId, 100, 1000);
        org.bson.Document before = stockItem(articleId, siteId);
        assertThat(before).isNotNull();

        // Une seconde, saisie par erreur à 3 000 F/kg, le tire vers le haut.
        String wrong = createReceipt(admin, producer, articleId, siteId, 100, 3000);
        org.bson.Document polluted = stockItem(articleId, siteId);
        assertThat(decimal(polluted, "cmup"))
                .as("le coût moyen a bien bougé avant l'annulation")
                .isNotEqualByComparingTo(decimal(before, "cmup"));

        cancel(admin, wrong, 200);

        org.bson.Document after = stockItem(articleId, siteId);
        assertThat(decimal(after, "quantity"))
                .as("la quantité revient à ce qu'elle était")
                .isEqualByComparingTo(decimal(before, "quantity"));
        assertThat(decimal(after, "cmup"))
                .as("le coût moyen aussi : c'est tout l'enjeu, une sortie seule ne le corrige pas")
                .isEqualByComparingTo(decimal(before, "cmup"));
    }

    @Test
    void a_cancelled_receipt_stays_readable_and_carries_its_reason() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String producer = createProducer(admin, "Diabaté");
        String receipt = createReceipt(admin, producer, articleId, siteId, 50, 1200);

        cancel(admin, receipt, 200);

        // On ne supprime rien : le reçu reste consultable, avec sa trace.
        givenAs(admin).when().get("/api/v1/producer-purchases/" + receipt)
                .then().statusCode(200)
                .body("data.status", equalTo("CANCELLED"))
                .body("data.cancellation.reason", equalTo("Poids saisi par erreur, sac non pesé"))
                .body("data.cancellation.cancelledAt", notNullValue());
    }

    @Test
    void cancelling_twice_is_refused() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String producer = createProducer(admin, "Traoré");
        String receipt = createReceipt(admin, producer, articleId, siteId, 40, 1100);

        cancel(admin, receipt, 200);
        // Sans ce refus, le stock serait défait deux fois.
        cancel(admin, receipt, 422);
    }

    @Test
    void cancelling_is_refused_when_the_material_has_already_left() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String producer = createProducer(admin, "Yao");
        String receipt = createReceipt(admin, producer, articleId, siteId, 80, 1000);

        // La matière part : une sortie manuelle vide le site.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "OUT",
                          "quantity": 80, "reason": "Expédition" }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(201);

        // Forcer l'annulation ferait passer le stock en négatif sans le dire.
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Erreur de saisie constatée trop tard\"}")
                .when().post("/api/v1/producer-purchases/" + receipt + "/cancel")
                .then().statusCode(422);

        givenAs(admin).when().get("/api/v1/producer-purchases/" + receipt)
                .then().statusCode(200)
                .body("data.status", equalTo("ACTIVE"));
    }

    @Test
    void a_reason_is_required() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);
        String producer = createProducer(admin, "Bamba");
        String receipt = createReceipt(admin, producer, articleId, siteId, 30, 900);

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"\"}")
                .when().post("/api/v1/producer-purchases/" + receipt + "/cancel")
                .then().statusCode(400);
    }
}
