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

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.containsString;

/**
 * Les files « À payer » et « À encaisser » en fichier (demandé le
 * 23/10/2026) : les mêmes colonnes que l'écran, sous le même filtre de
 * nature, dans les trois formats que tous les exports connaissent.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TreasuryQueueExportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-exp-" + TestFixtures.randomSlugSuffix(), "Coopérative Export");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Export";
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
    void la_file_a_payer_sort_en_fichier_avec_ses_colonnes() {
        UserEntity admin = admin();
        // Une demande d'avance approuvée attend son décaissement : elle est due.
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

        String csv = givenAs(admin).when().get("/api/v1/treasury/payables/export?format=csv")
                .then().statusCode(200).contentType(containsString("text/csv"))
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Tiers"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("KONE Adama"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Avance délégué"), csv);

        // Le filtre de nature de l'écran s'applique au fichier.
        String filtered = givenAs(admin).when()
                .get("/api/v1/treasury/payables/export?format=csv&kind=PETTY_CASH")
                .then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(filtered.contains("KONE Adama"), filtered);

        givenAs(admin).when().get("/api/v1/treasury/payables/export?format=xlsx")
                .then().statusCode(200)
                .contentType(containsString("spreadsheetml"));
    }

    @Test
    void la_file_a_encaisser_sort_en_fichier_meme_vide() {
        UserEntity admin = admin();
        String csv = givenAs(admin).when().get("/api/v1/treasury/receivables/export?format=csv")
                .then().statusCode(200).contentType(containsString("text/csv"))
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Tiers"), csv);
    }
}
