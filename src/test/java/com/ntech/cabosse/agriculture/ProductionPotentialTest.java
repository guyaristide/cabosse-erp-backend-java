package com.ntech.cabosse.agriculture;

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
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Potentiel de production d'une campagne : le chiffre de la structure est
 * la somme des estimations divisée par la somme des superficies, jamais la
 * moyenne des ratios individuels. Un producteur de 20 ha ne pèse pas comme
 * un producteur d'un hectare.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProductionPotentialTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-potentiel-" + TestFixtures.randomSlugSuffix(), "Coopérative Potentiel");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
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
        return u;
    }

    private String createCampaign(UserEntity admin) {
        return givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "label": "Campagne principale 2026", "campaignYear": 2026,
                          "startDate": "2025-09-01", "endDate": "2026-02-28",
                          "basePricePerKgFcfa": 1500 }
                        """)
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createMember(UserEntity admin, String lastName) {
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private void createParcel(UserEntity admin, String memberId, String campaignId,
                              String surfaceHa, String estimateKg) {
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "name": "Parcelle %s", "surfaceHa": %s,
                          "gpsCenter": [-4.02, 5.23], "memberId": "%s", "status": "ACTIVE",
                          "campaignYields": [
                            { "campaignId": "%s", "campaignYear": 2026, "estimateKg": %s }
                          ] }
                        """.formatted(surfaceHa, surfaceHa, memberId, campaignId, estimateKg))
                .when().post("/api/v1/parcels")
                .then().statusCode(201);
    }

    @Test
    void cooperative_potential_weighs_producers_by_surface() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);

        // 20 ha pour 18 000 kg → 900 kg/ha.
        String grand = createMember(admin, "Grand");
        createParcel(admin, grand, campaignId, "20", "18000");

        // 1 ha pour 2 000 kg → 2 000 kg/ha.
        String petit = createMember(admin, "Petit");
        createParcel(admin, petit, campaignId, "1", "2000");

        // Structure : 20 000 kg / 21 ha ≈ 952,38 kg/ha, et non (900+2000)/2.
        JsonPath body = givenAs(admin)
                .queryParam("campaignId", campaignId)
                .when().get("/api/v1/production-potential")
                .then().statusCode(200)
                .body("data.memberCount", equalTo(2))
                .body("data.parcelCount", equalTo(2))
                .body("data.campaignLabel", equalTo("Campagne principale 2026"))
                .body("data.rows", hasSize(2))
                .body("data.rows[0].memberName", equalTo("Grand"))
                .extract().jsonPath();

        assertThat(body.getDouble("data.totalSurfaceHa")).isEqualTo(21.0);
        assertThat(body.getDouble("data.totalEstimateKg")).isEqualTo(20000.0);
        assertThat(body.getDouble("data.potentialKgPerHa")).isEqualTo(952.38);
        assertThat(body.getDouble("data.rows[0].potentialKgPerHa")).isEqualTo(900.0);
        assertThat(body.getDouble("data.rows[1].potentialKgPerHa")).isEqualTo(2000.0);
    }

    @Test
    void producers_without_estimate_are_counted_apart() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);

        String avec = createMember(admin, "Avec");
        createParcel(admin, avec, campaignId, "10", "9000");

        // Parcelle sans estimation pour la campagne : le producteur sort des
        // totaux mais reste signalé, sinon la projection paraît complète.
        String sans = createMember(admin, "Sans");
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "name": "Parcelle sans estimation", "surfaceHa": 5,
                          "gpsCenter": [-4.02, 5.23], "memberId": "%s", "status": "ACTIVE" }
                        """.formatted(sans))
                .when().post("/api/v1/parcels")
                .then().statusCode(201);

        JsonPath body = givenAs(admin)
                .queryParam("campaignId", campaignId)
                .when().get("/api/v1/production-potential")
                .then().statusCode(200)
                .body("data.memberCount", equalTo(1))
                .body("data.membersWithoutEstimate", equalTo(1))
                .extract().jsonPath();

        assertThat(body.getDouble("data.totalSurfaceHa")).isEqualTo(10.0);
        assertThat(body.getDouble("data.potentialKgPerHa")).isEqualTo(900.0);
    }
}
