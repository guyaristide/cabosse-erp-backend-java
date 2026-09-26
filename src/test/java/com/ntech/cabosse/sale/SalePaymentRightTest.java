package com.ntech.cabosse.sale;

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

/**
 * Encaisser une vente demande le droit d'encaisser (26/09/2026).
 *
 * <p>{@code SALE_PAYMENT} existe, il est attribué à la caissière et au
 * directeur, et il ne gardait rien. Les deux chemins d'encaissement
 * exigeaient autre chose : le droit d'écriture de vente au détail, un
 * droit de comptabilité en gros. La caissière, dont c'est le métier, ne
 * pouvait donc encaisser ni l'un ni l'autre, et le directeur pas les
 * ventes en gros.</p>
 *
 * <p>Les droits d'origine restent acceptés : les retirer priverait
 * d'un geste ceux qui l'exercent aujourd'hui, et ce n'est pas au
 * logiciel d'en décider à la place de la structure.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SalePaymentRightTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-pay-" + TestFixtures.randomSlugSuffix(), "Coopérative Encaissement");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = user(Roles.TENANT_ADMIN, "admin");
        // L'achat producteur se règle en espèces : sans caisse
        // approvisionnée il échoue, et la vente n'a plus de stock.
        fundCashBox(u, 10_000_000);
        return u;
    }

    private UserEntity user(String role, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Test";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private UserEntity withRights(UserEntity admin, String prefix, String... permissions) {
        UserEntity u = user(Roles.USER, prefix);
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        String roleId = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }"
                        .formatted(prefix + "-" + TestFixtures.randomSlugSuffix(), perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + u.id)
                .then().statusCode(204);
        return u;
    }

    /** Une vente en gros encaissable, montée par l'administrateur. */
    private String commoditySale(UserEntity admin) {
        String siteCode = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\","
                        + "\"sellable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String customerId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"ZAMACOM\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kacou\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "nbSacs": 20, "weightKg": 1000,
                          "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201);

        return givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "weights": { "declaredKg": 520, "acceptedKg": 500 }, "pricePerKg": 1200 }
                        """.formatted(LocalDate.now(), customerId, articleId, siteId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse collect(UserEntity who, String saleId) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "paidOn": "%s", "amount": 100000, "method": "CASH",
                          "paymentRef": "Reçu caisse 001" }
                        """.formatted(LocalDate.now()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales/" + saleId + "/payments")
                .then();
    }

    @Test
    void la_caissiere_encaisse_une_vente_en_gros() {
        UserEntity admin = tenantAdmin();
        String saleId = commoditySale(admin);

        // Le droit fait pour ce geste. Il ne gardait rien, et
        // l'encaissement exigeait un droit de comptabilité : celle dont
        // c'est le métier ne pouvait pas encaisser.
        UserEntity caissiere = withRights(admin, "caissiere", "SALE_READ", "SALE_PAYMENT");
        collect(caissiere, saleId).statusCode(200);
    }

    @Test
    void le_droit_de_comptabilite_reste_accepte() {
        UserEntity admin = tenantAdmin();
        String saleId = commoditySale(admin);

        // Le retirer priverait le comptable d'un geste qu'il exerce
        // aujourd'hui, et ce n'est pas au logiciel d'en décider.
        UserEntity comptable = withRights(admin, "comptable",
                "SALE_READ", "ACCOUNTING_READ", "ACCOUNTING_WRITE");
        collect(comptable, saleId).statusCode(200);
    }

    @Test
    void lire_les_ventes_ne_suffit_pas_pour_encaisser() {
        UserEntity admin = tenantAdmin();
        String saleId = commoditySale(admin);

        // Le profil du conseil ne porte que des lectures : le refus est
        // ici la bonne réponse, et non un défaut.
        UserEntity pca = withRights(admin, "pca", "SALE_READ", "ACCOUNTING_READ");
        collect(pca, saleId).statusCode(403);
    }
}
