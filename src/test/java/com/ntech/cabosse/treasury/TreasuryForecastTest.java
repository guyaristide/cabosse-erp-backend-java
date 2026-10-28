package com.ntech.cabosse.treasury;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Le solde net théorique à date (demandé le 28/10/2026) : réel + à
 * encaisser − à payer, pour savoir si l'on pourra faire face aux
 * engagements.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TreasuryForecastTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-fc-" + TestFixtures.randomSlugSuffix(), "Coopérative Prévision");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Prévision";
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
    void the_theoretical_balance_is_the_real_one_plus_receivables_minus_payables() {
        UserEntity admin = admin();

        // Rien n'est dû : le théorique vaut le réel.
        var before = givenAs(admin).when().get("/api/v1/treasury/forecast")
                .then().statusCode(200)
                .body("data.hiddenAccounts", equalTo(0))
                .body("data.realBalance", notNullValue())
                .body("data.theoreticalBalance", notNullValue())
                .extract().jsonPath();
        BigDecimal real = new BigDecimal(before.getString("data.realBalance"));
        BigDecimal collect0 = new BigDecimal(before.getString("data.toCollect"));
        BigDecimal pay0 = new BigDecimal(before.getString("data.toPay"));
        assertEquals(0, real.add(collect0).subtract(pay0)
                .compareTo(new BigDecimal(before.getString("data.theoreticalBalance"))));

        // Une avance approuvée attend son décaissement : elle entre dans « à payer ».
        String sectionId = givenAs(admin).contentType("application/json")
                .body("{\"code\":\"MEAGUI\",\"name\":\"Section Méagui\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"KONE Adama\",\"collector\":true,\"sectionId\":\"" + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 1200000, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200);

        var after = givenAs(admin).when().get("/api/v1/treasury/forecast")
                .then().statusCode(200).extract().jsonPath();
        BigDecimal pay1 = new BigDecimal(after.getString("data.toPay"));
        BigDecimal collect1 = new BigDecimal(after.getString("data.toCollect"));
        BigDecimal real1 = new BigDecimal(after.getString("data.realBalance"));
        BigDecimal theoretical1 = new BigDecimal(after.getString("data.theoreticalBalance"));
        assertEquals(0, pay1.subtract(pay0).compareTo(new BigDecimal("1200000")), "l'avance approuvée est due");
        assertEquals(0, real1.compareTo(real), "rien n'a encore été décaissé");
        assertEquals(0, real1.add(collect1).subtract(pay1).compareTo(theoretical1));
        assertEquals(0, theoretical1.compareTo(real.subtract(new BigDecimal("1200000")).add(collect1)));
    }
}
