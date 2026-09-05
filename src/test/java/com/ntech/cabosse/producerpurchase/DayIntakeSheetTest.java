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
import static org.hamcrest.Matchers.hasSize;

/**
 * La fiche des entrées du jour suit le carnet du magasinier (CE-185).
 *
 * <p>Ligne d'ouverture au report du stock, une ligne par reçu dans
 * l'ordre de saisie, cumuls progressifs en quantité et en sacs, totaux.
 * Les chiffres calquent l'exemple de l'expert : 3 200 kg de report, deux
 * livraisons, et le cumul qui finit là où le carnet le dit.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DayIntakeSheetTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-fiche-" + TestFixtures.randomSlugSuffix(), "Coopérative Fiche du jour");
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
        return u;
    }

    @Test
    void the_sheet_carries_the_opening_and_running_totals() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        // Le report du carnet : 3 200 kg déjà en magasin, ancrés à la
        // veille. La photo d'ouverture se prend à minuit : un amorçage
        // daté du jour même arriverait « après » elle, et le report est
        // par nature un héritage des jours précédents.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 3200, "unitPrice": 1200 } ] }
                        """.formatted(siteId,
                        java.time.Instant.now().minus(java.time.Duration.ofDays(1)), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        String m1 = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Diarra\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String m2 = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kobenan\",\"gender\":\"FEMALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        LocalDate today = LocalDate.now();
        // Deux livraisons du jour, dans l'ordre du carnet : 1 455 kg en 23
        // sacs, puis 193 kg en 3 sacs.
        for (String[] delivery : new String[][]{
                {m1, "23", "1455"}, {m2, "3", "193"}}) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "nbSacs": %s, "weightKg": %s,
                              "guaranteedPricePerKg": 1200, "paymentMethod": "CASH" }
                            """.formatted(today, delivery[0], articleId, siteId, delivery[1], delivery[2]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        givenAs(admin)
                .queryParam("date", today.toString())
                .queryParam("siteId", siteId)
                .when().get("/api/v1/producer-purchases/day-sheet")
                .then().statusCode(200)
                // La photo rend un décimal (3200.0) : on compare en flottant.
                .body("data.openingQuantity", equalTo(3200.0F))
                .body("data.rows", hasSize(2))
                .body("data.rows[0].cumulativeQuantity", equalTo(4655.0F))
                .body("data.rows[0].cumulativeBags", equalTo(23))
                .body("data.rows[1].cumulativeQuantity", equalTo(4848.0F))
                .body("data.rows[1].cumulativeBags", equalTo(26))
                .body("data.rows[1].supplierKind", equalTo("PRODUCER"))
                .body("data.totalWeightKg", equalTo(1648))
                .body("data.totalAmount", equalTo(1977600))
                .body("data.closingQuantity", equalTo(4848.0F));

        // L'export sort dans les trois formats, ouverture et totaux inclus.
        givenAs(admin)
                .queryParam("date", today.toString())
                .queryParam("siteId", siteId)
                .queryParam("format", "csv")
                .when().get("/api/v1/producer-purchases/day-sheet/export")
                .then().statusCode(200);
    }
}
