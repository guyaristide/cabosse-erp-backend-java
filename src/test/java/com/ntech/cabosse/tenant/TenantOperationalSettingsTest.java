package com.ntech.cabosse.tenant;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Réglages opérationnels du tenant (backlog MEM-02, STK-04, CPT-03
 * paramétrés) : défauts exposés, pièce « part sociale » générée à la
 * création d'un membre actif, politique de réouverture PLATFORM_ONLY.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantOperationalSettingsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private record Ctx(TenantEntity tenant, UserEntity admin) {}

    private Ctx tenantAdmin(String slugPrefix) {
        TenantEntity tenant = fixtures.createActiveTenant(
                slugPrefix + "-" + TestFixtures.randomSlugSuffix(), "Coopérative Réglages");
        // La capacité membres dépend du modèle d'organisation.
        tenant.organizationModel = com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
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
        return new Ctx(tenant, u);
    }

    @Test
    void preferences_expose_defaults() {
        Ctx ctx = tenantAdmin("coop-defaults");
        givenAs(ctx.admin())
                .when().get("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.postMemberCapitalEntries", equalTo(true))
                .body("data.memberCapitalAccount", equalTo("101000"))
                .body("data.postStockTransferEntries", equalTo(false))
                .body("data.inventoryAlertThresholdPct", equalTo(5))
                .body("data.inventoryAlertThresholdAmount", equalTo(100000))
                .body("data.periodReopenPolicy", equalTo("TENANT_ADMIN"))
                .body("data.vatDeductibleAccount", equalTo("445660"))
                .body("data.memberCapitalFlow", equalTo("DIRECT"))
                .body("data.analyticsIncludeStockTransfers", equalTo(false))
                .body("data.blockProductionOnStockShortage", equalTo(true))
                .body("data.stockMinWarningPct", equalTo(20));
    }

    @Test
    void production_guard_and_warning_pct_round_trip() {
        Ctx ctx = tenantAdmin("coop-prodguard");
        givenAs(ctx.admin())
                .contentType("application/json")
                .body("{\"blockProductionOnStockShortage\":false,\"stockMinWarningPct\":30}")
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.blockProductionOnStockShortage", equalTo(false))
                .body("data.stockMinWarningPct", equalTo(30));
    }

    @Test
    void subscription_flow_posts_souscription_then_liberation() {
        Ctx ctx = tenantAdmin("coop-souscription");
        givenAs(ctx.admin())
                .contentType("application/json")
                .body("{\"memberCapitalFlow\":\"SUBSCRIPTION\",\"memberCapitalAccount\":\"1018\"}")
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.memberCapitalFlow", equalTo("SUBSCRIPTION"))
                .body("data.memberCapitalAccount", equalTo("1018"));

        String memberId = givenAs(ctx.admin())
                .contentType("application/json")
                .body("""
                        { "name": "Fatou Bamba", "civilStatus": "UNKNOWN",
                          "partsSocialesAmount": 30000, "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");

        // Deux pièces : souscription (461/1018) puis libération (trésorerie/461).
        givenAs(ctx.admin())
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(2));

        // La radiation contre-passe les deux pièces du cycle.
        givenAs(ctx.admin())
                .contentType("application/json")
                .body("{\"reason\":\"Départ volontaire\"}")
                .when().post("/api/v1/members/" + memberId + "/retire")
                .then().statusCode(200);

        givenAs(ctx.admin())
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(4));
    }

    @Test
    void active_member_with_parts_sociales_generates_capital_piece() {
        Ctx ctx = tenantAdmin("coop-capital");
        givenAs(ctx.admin())
                .contentType("application/json")
                .body("""
                        { "name": "Aïcha Diabaté", "civilStatus": "UNKNOWN",
                          "partsSocialesAmount": 25000, "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201);

        givenAs(ctx.admin())
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].totalDebit", equalTo(25000))
                .body("data.items[0].sourceType", equalTo("MEMBER_CAPITAL"));
    }

    @Test
    void pending_member_generates_no_piece_until_approved() {
        Ctx ctx = tenantAdmin("coop-pending");
        String memberId = givenAs(ctx.admin())
                .contentType("application/json")
                .body("""
                        { "name": "Mamadou Koné", "civilStatus": "UNKNOWN",
                          "partsSocialesAmount": 10000, "status": "PENDING" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(ctx.admin())
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(0));

        givenAs(ctx.admin())
                .contentType("application/json")
                .when().post("/api/v1/members/" + memberId + "/approve")
                .then().statusCode(200)
                .body("data.status", equalTo("ACTIVE"));

        givenAs(ctx.admin())
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].sourceType", equalTo("MEMBER_CAPITAL"));
    }

    @Test
    void platform_only_policy_blocks_tenant_admin_reopen() {
        Ctx ctx = tenantAdmin("coop-policy");
        TenantEntity tenant = tenants.findById(ctx.tenant().id);
        if (tenant.preferences == null) tenant.preferences = new TenantPreferences();
        tenant.preferences.periodReopenPolicy = TenantPreferences.REOPEN_PLATFORM_ONLY;
        tenants.update(tenant);

        String lastMonth = YearMonth.now().minusMonths(1).toString();
        givenAs(ctx.admin())
                .contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/lock")
                .then().statusCode(200);

        givenAs(ctx.admin())
                .contentType("application/json")
                .body("{\"reason\":\"Correction\"}")
                .when().post("/api/v1/accounting/periods/" + lastMonth + "/reopen")
                .then().statusCode(422);
    }
}
