package com.ntech.cabosse.collector;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les deux pièces que le conseil demande avant de se décider : une par
 * avance versée, et le relevé du compte du délégué à une date.
 *
 * <p>Jusqu'ici l'avance et son compte ne vivaient qu'à l'écran. Ce qui
 * manquait, c'est le document que l'on remet et que l'on signe (demandé
 * le 14/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceVoucherTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-piece-" + TestFixtures.randomSlugSuffix(), "Coopérative Pièces");
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
    void chaque_avance_versee_a_sa_piece_et_le_compte_a_son_releve() {
        UserEntity admin = admin();
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"code\":\"del-" + TestFixtures.randomSlugSuffix()
                        + "\",\"name\":\"Assi Cyprien\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        String advanceId = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": 10000000, "paymentMethod": "CHEQUE" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");

        // Une avance qui n'est pas sortie n'a pas de pièce : elle n'a
        // encore rien mis dans la main de personne.
        givenAs(admin).when().get("/api/v1/collector-advances/" + advanceId + "/voucher")
                .then().statusCode(422);

        givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                .then().statusCode(200);
        givenAs(admin).contentType("application/json")
                .body("{ \"paymentRef\": \"959\", \"acknowledgeInsufficientBalance\": true }")
                .when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                .then().statusCode(200);

        byte[] voucher = givenAs(admin)
                .when().get("/api/v1/collector-advances/" + advanceId + "/voucher")
                .then().statusCode(200)
                .extract().asByteArray();
        assertThat(new String(voucher, 0, 5)).startsWith("%PDF-");
        assertThat(voucher.length).isGreaterThan(1000);

        byte[] statement = givenAs(admin)
                .queryParam("asOf", LocalDate.now().toString())
                .when().get("/api/v1/collector-advances/ledger/" + delegateId + "/statement")
                .then().statusCode(200)
                .extract().asByteArray();
        assertThat(new String(statement, 0, 5)).startsWith("%PDF-");
        assertThat(statement.length).isGreaterThan(1000);
    }
}
