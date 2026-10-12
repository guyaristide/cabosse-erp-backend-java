package com.ntech.cabosse.accounting;

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
import static org.hamcrest.Matchers.hasSize;

/**
 * Le magasin et le journal, côte à côte.
 *
 * <p>Le stock de départ reste un constat de quantité et ne passe aucune
 * écriture : c'est le comptable qui porte les à-nouveaux (décision de
 * l'expert du 12/10/2026). Rien ne rapprochait les deux chiffres, et un
 * compte de stock pouvait devenir créditeur sans que personne ne sache
 * d'où cela venait.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockReconciliationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rap-" + TestFixtures.randomSlugSuffix(), "Coopérative Rapprochement");
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
    void l_ecart_entre_le_magasin_et_le_journal_se_lit() {
        UserEntity admin = admin();
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"m-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"MERCHANDISE\",\"name\":\"Fèves\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        // Le magasin amorce 1 000 kg à 1 000 : un million en magasin, et
        // rien au journal, l'amorçage ne passant aucune écriture.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 1000, "unitPrice": 1000 } ] }
                        """.formatted(siteId, Instant.now(), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/accounting/stock-reconciliation")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].account", equalTo("310000"))
                .body("data[0].stockValue", equalTo(1000000.0F))
                .body("data[0].ledgerBalance", equalTo(0))
                // L'écart est exactement ce qu'il reste à porter en
                // à-nouveaux : sans cet état, personne ne le voyait.
                .body("data[0].gap", equalTo(1000000.0F));
    }
}
