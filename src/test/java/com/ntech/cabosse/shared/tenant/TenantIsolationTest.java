package com.ntech.cabosse.shared.tenant;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Isolation stricte entre tenants (base par tenant) : les données du
 * tenant A ne doivent jamais être visibles ni adressables depuis une
 * session du tenant B. Test de non-régression du TenantContextFilter.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantIsolationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity adminOf(String slugPrefix) {
        TenantEntity tenant = fixtures.createActiveTenant(
                slugPrefix + "-" + TestFixtures.randomSlugSuffix(), "Coop " + slugPrefix);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = slugPrefix;
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
    void data_of_tenant_a_is_invisible_and_unaddressable_from_tenant_b() {
        UserEntity adminA = adminOf("iso-a");
        UserEntity adminB = adminOf("iso-b");

        String articleId = givenAs(adminA)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Fèves du tenant A", "unit": "kg" }
                        """)
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .extract().path("data.id");

        // Liste vide côté B, accès direct par id impossible.
        givenAs(adminB)
                .when().get("/api/v1/articles?page=0&perPage=20")
                .then().statusCode(200)
                .body("data.total", equalTo(0));

        givenAs(adminB)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Tentative B", "unit": "kg" }
                        """)
                .when().put("/api/v1/articles/" + articleId)
                .then().statusCode(404);

        // Le journal comptable de B ignore les pièces de A.
        givenAs(adminA)
                .contentType("application/json")
                .body("""
                        { "date": "2026-06-15", "libelle": "Pièce du tenant A",
                          "lines": [
                            { "account": "601", "libelle": "d", "debitFcfa": 1000 },
                            { "account": "401", "libelle": "c", "creditFcfa": 1000 }
                          ] }
                        """)
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201);
        String odId = givenAs(adminA)
                .when().get("/api/v1/accounting/od?status=DRAFT")
                .then().statusCode(200)
                .extract().path("data.items[0].id");
        givenAs(adminA)
                .contentType("application/json")
                .when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);

        givenAs(adminB)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(0));

        givenAs(adminA)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1));
    }

    @Test
    void stock_of_tenant_a_is_invisible_from_tenant_b() {
        UserEntity adminA = adminOf("iso-stock-a");
        UserEntity adminB = adminOf("iso-stock-b");

        givenAs(adminA)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "Karité A", "unit": "kg" }
                        """)
                .when().post("/api/v1/articles")
                .then().statusCode(201);

        givenAs(adminB)
                .when().get("/api/v1/articles?page=0&perPage=20")
                .then().statusCode(200)
                .body("data.total", equalTo(0));
    }
}
