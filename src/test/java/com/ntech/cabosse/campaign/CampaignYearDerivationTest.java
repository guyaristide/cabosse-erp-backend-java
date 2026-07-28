package com.ntech.cabosse.campaign;

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
import static org.hamcrest.Matchers.startsWith;

/**
 * L'année d'une campagne n'est plus saisie : elle se déduit de la date
 * d'ouverture. Une saison à cheval sur deux années civiles était sinon
 * datée différemment selon la personne qui la créait, et l'âge des
 * plantations du registre variait d'autant.
 *
 * <p>Conséquence à vérifier aussi : les apports d'un membre se regroupent
 * par campagne et non par année. Deux campagnes ouvertes la même année
 * (principale puis intermédiaire) n'ont ni la même période ni le même prix
 * de base ; leurs apports ne se cumulent pas.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignYearDerivationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-campagne-" + TestFixtures.randomSlugSuffix(), "Coopérative Campagne");
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

    private String createCampaign(UserEntity admin, String label, String start, String end) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKgFcfa": 1500 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createMember(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createParcel(UserEntity admin, String memberId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "Parcelle Sud", "surfaceHa": 4,
                          "gpsCenter": [-4.02, 5.23], "memberId": "%s", "status": "ACTIVE" }
                        """.formatted(memberId))
                .when().post("/api/v1/parcels")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private void createHarvest(UserEntity admin, String parcelId, String campaignId, String date) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "parcelId": "%s", "campaignId": "%s", "harvestDate": "%s",
                          "freshBeansKg": 500 }
                        """.formatted(parcelId, campaignId, date))
                .when().post("/api/v1/harvests")
                .then().statusCode(201);
    }

    @Test
    void the_main_and_intermediate_campaigns_coexist() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();

        // Principale terminée, intermédiaire en cours : les deux ouvertes.
        createCampaign(admin, "Principale",
                today.minusMonths(11).toString(), today.minusMonths(5).toString());
        String intermediaire = createCampaign(admin, "Intermédiaire",
                today.minusMonths(4).toString(), today.plusMonths(1).toString());

        givenAs(admin).when().get("/api/v1/campaigns")
                .then().statusCode(200)
                .body("data", hasSize(2))
                .body("data.status", org.hamcrest.Matchers.everyItem(equalTo("OPEN")));

        // La courante est celle dont la période couvre le jour, pas la
        // première ouverte ni la dernière créée.
        givenAs(admin).when().get("/api/v1/campaigns/current")
                .then().statusCode(200)
                .body("data.id", equalTo(intermediaire));
    }

    @Test
    void campaign_year_follows_the_opening_date() {
        UserEntity admin = tenantAdmin();

        // Saison 2025-2026 ouverte en septembre : c'est une campagne 2025,
        // quelle que soit l'année qui figure dans le libellé.
        String id = createCampaign(admin, "Campagne 2025-2026", "2025-09-01", "2026-02-28");

        givenAs(admin).when().get("/api/v1/campaigns/" + id)
                .then().statusCode(200)
                .body("data.campaignYear", equalTo(2025))
                .body("data.code", startsWith("CMP-2025-"));
    }

    @Test
    void correcting_the_opening_date_realigns_the_year() {
        UserEntity admin = tenantAdmin();
        String id = createCampaign(admin, "Campagne intermédiaire", "2025-09-01", "2026-02-28");
        String code = givenAs(admin).when().get("/api/v1/campaigns/" + id)
                .then().statusCode(200).extract().path("data.code");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "Campagne intermédiaire", "startDate": "2026-03-01",
                          "endDate": "2026-08-31", "basePricePerKgFcfa": 1500 }
                        """)
                .when().put("/api/v1/campaigns/" + id)
                .then().statusCode(200)
                .body("data.campaignYear", equalTo(2026))
                // La référence déjà émise ne bouge pas : elle circule sur
                // des documents imprimés.
                .body("data.code", equalTo(code));
    }

    @Test
    void two_campaigns_of_the_same_year_do_not_merge_contributions() {
        UserEntity admin = tenantAdmin();
        String memberId = createMember(admin, "Koffi");
        String parcelId = createParcel(admin, memberId);

        // Les deux restent ouvertes : la principale n'est pas clôturée le
        // jour où l'intermédiaire démarre.
        String principale = createCampaign(admin, "Principale", "2026-01-05", "2026-04-30");
        createHarvest(admin, parcelId, principale, "2026-02-10");

        String intermediaire = createCampaign(admin, "Intermédiaire", "2026-05-02", "2026-08-31");
        createHarvest(admin, parcelId, intermediaire, "2026-06-15");

        givenAs(admin).when().get("/api/v1/members/" + memberId + "/contributions")
                .then().statusCode(200)
                .body("data.campaigns", hasSize(2))
                .body("data.campaigns.campaignLabel",
                        org.hamcrest.Matchers.containsInAnyOrder("Principale", "Intermédiaire"))
                .body("data.campaigns[0].harvestCount", equalTo(1))
                .body("data.campaigns[1].harvestCount", equalTo(1));
    }
}
