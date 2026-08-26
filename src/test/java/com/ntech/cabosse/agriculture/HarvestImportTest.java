package com.ntech.cabosse.agriculture;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Import de récoltes. Ce qui lui est propre : la dépendance aux parcelles,
 * l'identification d'une récolte par sa parcelle et sa date, et la date qui
 * sort de la période de campagne.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class HarvestImportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rec-" + TestFixtures.randomSlugSuffix(), "Coopérative Récoltes");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
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
                        { "label": "Campagne principale 2026",
                          "startDate": "2025-09-01", "endDate": "2026-02-28",
                          "basePricePerKgFcfa": 1500 }
                        """)
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createParcel(UserEntity admin) {
        return givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "code": "PR-REC-1", "name": "Parcelle récolte", "surfaceHa": 4,
                          "gpsCenter": [-4.02, 5.23], "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/parcels")
                .then().statusCode(201)
                .extract().path("data.code");
    }

    @Test
    void an_unknown_parcel_is_an_error_not_a_warning() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        givenAs(admin)
                .contentType("application/json")
                .queryParam("campaignId", campaignId)
                .body("""
                        [
                          { "rowNumber": 1, "parcelCode": "PR-REC-1", "harvestDate": "12/11/2025",
                            "cabossesKg": "1250", "freshBeansKg": "480" },
                          { "rowNumber": 2, "parcelCode": "PR-INCONNUE", "harvestDate": "12/11/2025",
                            "cabossesKg": "300" }
                        ]
                        """)
                .when().post("/api/v1/harvests/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.invalidRows", equalTo(1))
                .body("data.rows[1].status", equalTo("INVALID"))
                .body("data.rows[1].issues[0].field", equalTo("parcelCode"));
    }

    @Test
    void a_parcel_code_in_the_parcel_name_column_still_matches() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        // Fichier retravaillé sur le terrain : le code plantation atterrit
        // dans la colonne « Parcelle », relue comme un nom.
        givenAs(admin)
                .contentType("application/json")
                .queryParam("campaignId", campaignId)
                .body("""
                        [
                          { "rowNumber": 1, "parcelCode": "HV-2026-0001",
                            "parcelName": "PR-REC-1", "harvestDate": "12/11/2025",
                            "freshBeansKg": "500" }
                        ]
                        """)
                .when().post("/api/v1/harvests/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.rows[0].normalized.parcelCode", equalTo("PR-REC-1"));
    }

    @Test
    void a_date_outside_the_campaign_is_a_warning_the_user_can_accept() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        String body = """
                [
                  { "rowNumber": 1, "parcelCode": "PR-REC-1", "harvestDate": "15/07/2026",
                    "cabossesKg": "900" }
                ]
                """;

        givenAs(admin).contentType("application/json").queryParam("campaignId", campaignId).body(body)
                .when().post("/api/v1/harvests/import/preview")
                .then().statusCode(200)
                .body("data.warningRows", equalTo(1));

        givenAs(admin).contentType("application/json").queryParam("campaignId", campaignId).body(body)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedCount", equalTo(1));

        givenAs(admin).contentType("application/json")
                .queryParam("campaignId", campaignId)
                .queryParam("includeWarnings", true)
                .body(body)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.campaignLabel", equalTo("Campagne principale 2026"));
    }

    @Test
    void the_same_parcel_and_date_updates_instead_of_duplicating() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        String body = """
                [
                  { "rowNumber": 1, "parcelCode": "PR-REC-1", "harvestDate": "12/11/2025",
                    "cabossesKg": "1250", "freshBeansKg": "480" }
                ]
                """;

        givenAs(admin).contentType("application/json").queryParam("campaignId", campaignId).body(body)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1));

        givenAs(admin).contentType("application/json").queryParam("campaignId", campaignId).body(body)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.updatedCount", equalTo(1));

        givenAs(admin)
                .when().get("/api/v1/harvests")
                .then().statusCode(200)
                .body("data.total", equalTo(1));
    }

    @Test
    void an_import_update_keeps_quantities_absent_from_the_file() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        givenAs(admin).contentType("application/json")
                .queryParam("campaignId", campaignId)
                .body("""
                        [ { "rowNumber": 1, "parcelCode": "PR-REC-1",
                            "harvestDate": "12/11/2025",
                            "cabossesKg": "1000", "freshBeansKg": "400" } ]
                        """)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1));

        // Correction des seules cabosses : les fèves fraîches restent.
        givenAs(admin).contentType("application/json")
                .queryParam("campaignId", campaignId)
                .body("""
                        [ { "rowNumber": 1, "parcelCode": "PR-REC-1",
                            "harvestDate": "12/11/2025", "cabossesKg": "1100" } ]
                        """)
                .when().post("/api/v1/harvests/import/commit")
                .then().statusCode(200)
                .body("data.updatedCount", equalTo(1));

        givenAs(admin).when().get("/api/v1/harvests")
                .then().statusCode(200)
                .body("data.items[0].cabossesKg", equalTo(1100))
                .body("data.items[0].freshBeansKg", equalTo(400));
    }

    @Test
    void a_row_without_any_quantity_is_refused() {
        UserEntity admin = tenantAdmin();
        String campaignId = createCampaign(admin);
        createParcel(admin);

        givenAs(admin)
                .contentType("application/json")
                .queryParam("campaignId", campaignId)
                .body("""
                        [
                          { "rowNumber": 1, "parcelCode": "PR-REC-1", "harvestDate": "12/11/2025" }
                        ]
                        """)
                .when().post("/api/v1/harvests/import/preview")
                .then().statusCode(200)
                .body("data.invalidRows", equalTo(1))
                .body("data.rows[0].issues[0].field", equalTo("cabossesKg"));
    }
}
