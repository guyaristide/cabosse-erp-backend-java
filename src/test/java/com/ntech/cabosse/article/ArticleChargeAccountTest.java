package com.ntech.cabosse.article;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Compte d'achat par article (backlog CPT-11) : le compte porté par la
 * fiche article prime sur la résolution par type ; à défaut, le mapping
 * 601/604/6081 s'applique.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ArticleChargeAccountTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-charge-" + TestFixtures.randomSlugSuffix(), "Coopérative Comptes");
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

    @Test
    void article_account_overrides_type_mapping_on_direct_receipt() {
        UserEntity admin = tenantAdmin();

        String articleId = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Cacao certifié RA",
                          "unit": "kg", "purchaseChargeAccount": "6012" }
                        """)
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .body("data.purchaseChargeAccount", equalTo("6012"))
                .extract().path("data.id");

        String supplierId = givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"Producteur Kouassi\"}")
                .when().post("/api/v1/suppliers")
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "receivedDate": "%s",
                          "lines": [ { "supplierId": "%s", "quantity": 100, "unitPriceFcfa": 1500 } ] }
                        """.formatted(articleId, LocalDate.now(), supplierId))
                .when().post("/api/v1/direct-receipts")
                .then().statusCode(201);

        // La pièce débite le compte de la fiche article, pas le 601 par type.
        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].sourceType", equalTo("DIRECT_RECEIPT"))
                .body("data.items[0].entries.syscohadaAccount", hasItem("6012"));
    }

    @Test
    void type_mapping_still_applies_without_article_account() {
        UserEntity admin = tenantAdmin();

        String articleId = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Cacao marchand", "unit": "kg" }
                        """)
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .extract().path("data.id");

        String supplierId = givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"Producteur Bamba\"}")
                .when().post("/api/v1/suppliers")
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "articleId": "%s", "receivedDate": "%s",
                          "lines": [ { "supplierId": "%s", "quantity": 50, "unitPriceFcfa": 1200 } ] }
                        """.formatted(articleId, LocalDate.now(), supplierId))
                .when().post("/api/v1/direct-receipts")
                .then().statusCode(201);

        givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].entries.syscohadaAccount", hasItem("601"));
    }
}
