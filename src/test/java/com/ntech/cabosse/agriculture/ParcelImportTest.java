package com.ntech.cabosse.agriculture;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto.FieldIssue;
import com.ntech.cabosse.agriculture.parcel.service.ParcelImportService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Import de parcelles. Ce qui distingue cet import de celui des
 * producteurs : les coordonnées arrivent dans deux écritures, le
 * rattachement au producteur peut échouer, et le contour ne s'importe pas.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ParcelImportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-parc-" + TestFixtures.randomSlugSuffix(), "Coopérative Parcelles");
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

    private String createMember(UserEntity admin, String lastName, String firstName) {
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"firstName\":\"" + firstName
                        + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.code");
    }

    @Test
    void coordinates_are_read_in_decimal_and_in_degrees_minutes_seconds() {
        List<FieldIssue> issues = new ArrayList<>();

        assertThat(ParcelImportService.parseCoordinate("5.236830", "latitude", issues))
                .isEqualTo(5.236830);
        // Virgule décimale française.
        assertThat(ParcelImportService.parseCoordinate("-4,020996", "longitude", issues))
                .isEqualTo(-4.020996);
        // Degrés minutes secondes, orientation ouest en notation française.
        Double dms = ParcelImportService.parseCoordinate("6°38'12.5\"O", "longitude", issues);
        assertThat(dms).isNotNull();
        assertThat(dms).isCloseTo(-6.636806, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(issues).isEmpty();

        assertThat(ParcelImportService.parseCoordinate("nord-ouest", "latitude", issues)).isNull();
        assertThat(issues).hasSize(1);
    }

    @Test
    void an_unknown_producer_makes_the_row_a_warning_not_an_error() {
        UserEntity admin = tenantAdmin();
        createMember(admin, "N'Guessan", "Konan");

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        [
                          { "rowNumber": 1, "name": "Parcelle 1",
                            "producerName": "N'Guessan Konan",
                            "surfaceHa": "15,2", "latitude": "5.236830", "longitude": "-4.020996",
                            "crop": "Cacao", "mainCrop": "Oui" },
                          { "rowNumber": 2, "name": "Parcelle inconnue",
                            "producerName": "Producteur Fantôme",
                            "surfaceHa": "3", "latitude": "5.1", "longitude": "-4.1" }
                        ]
                        """)
                .when().post("/api/v1/parcels/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                .body("data.warningRows", equalTo(1))
                .body("data.rows[0].status", equalTo("READY"))
                .body("data.rows[0].normalized.memberName", equalTo("N'Guessan Konan"))
                .body("data.rows[1].status", equalTo("WARNING"));
    }

    @Test
    void commit_creates_then_updates_and_opens_the_crop_referential() {
        UserEntity admin = tenantAdmin();
        String memberCode = createMember(admin, "Doumbia", "Seydou");

        String body = """
                [
                  { "rowNumber": 1, "code": "PR-IMPORT-1", "name": "Parcelle Méagui",
                    "producerCode": "%s", "surfaceHa": "4",
                    "latitude": "5.236830", "longitude": "-4.020996",
                    "crop": "Cacao", "mainCrop": "Oui", "region": "Nawa", "department": "Méagui",
                    "plantingDate": "02/10/2003", "status": "En production" }
                ]
                """.formatted(memberCode);

        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/parcels/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.updatedCount", equalTo(0))
                .body("data.orphanParcels", equalTo(0))
                .body("data.createdCrops", hasSize(1))
                .body("data.createdRegions", hasSize(1))
                .body("data.createdDepartments", hasSize(1));

        // Deuxième passe : mise à jour par le code plantation, pas de doublon.
        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/parcels/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.updatedCount", equalTo(1))
                .body("data.createdCrops", hasSize(0));

        givenAs(admin)
                .when().get("/api/v1/parcels")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].mainCrop", equalTo(true))
                // Le contour ne s'importe pas : seul le point central est posé.
                .body("data.items[0].gpsPolygonCoordinates", org.hamcrest.Matchers.nullValue())
                .body("data.items[0].gpsCenter", hasSize(2))
                // L'année de plantation se dérive de la date complète.
                .body("data.items[0].plantingYear", equalTo(2003));
    }

    @Test
    void orphan_parcels_are_created_only_when_explicitly_accepted() {
        UserEntity admin = tenantAdmin();
        String body = """
                [
                  { "rowNumber": 1, "name": "Parcelle orpheline",
                    "producerName": "Inconnu au bataillon",
                    "surfaceHa": "2", "latitude": "5.1", "longitude": "-4.1" }
                ]
                """;

        givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/parcels/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(0))
                .body("data.skippedCount", equalTo(1));

        givenAs(admin).contentType("application/json").body(body)
                .queryParam("includeWarnings", true)
                .when().post("/api/v1/parcels/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.orphanParcels", equalTo(1));
    }
}
