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

/**
 * Une opération appartient à la campagne de sa propre date, pas à celle du
 * jour où on la saisit.
 *
 * <p>Le terrain saisit en retard : un délégué rentre ses reçus de novembre
 * en mars, quand le réseau revient. Le rattachement suivait alors la
 * campagne courante, si bien que la collecte de la principale grossissait
 * l'intermédiaire. Rien ne le signalait, et les états de campagne étaient
 * faux sans être visiblement faux.</p>
 *
 * <p>Le tenant peut revenir au comportement d'avant avec le réglage
 * « Rattachement à la campagne » sur « au choix » : la campagne courante
 * est alors proposée et le rattachement se corrige à la main.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignAssignmentByDateTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rattachement-" + TestFixtures.randomSlugSuffix(), "Coopérative Rattachement");
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

    private String createCampaign(UserEntity admin, String label, LocalDate start, LocalDate end) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 1500 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createParcel(UserEntity admin) {
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kouassi\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "Parcelle Sud", "surfaceHa": 4,
                          "gpsCenter": [-4.02, 5.23], "memberId": "%s", "status": "ACTIVE" }
                        """.formatted(memberId))
                .when().post("/api/v1/parcels")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    /** Récolte sans campagne explicite : c'est la date qui doit trancher. */
    private String harvestOn(UserEntity admin, String parcelId, LocalDate date) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "parcelId": "%s", "harvestDate": "%s", "freshBeansKg": 500 }
                        """.formatted(parcelId, date))
                .when().post("/api/v1/harvests")
                .then().statusCode(201)
                .extract().path("data.campaignId");
    }

    private void setAssignmentMode(UserEntity admin, String mode) {
        givenAs(admin).contentType("application/json")
                .body("{\"campaignAssignmentMode\":\"%s\"}".formatted(mode))
                .when().put("/api/v1/me/tenant/preferences")
                .then().statusCode(200);
    }

    @Test
    void a_backdated_harvest_joins_the_campaign_of_its_own_date() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();

        // Deux campagnes de la même saison, celle d'avant refermée dans les
        // faits par sa date de fin, celle d'aujourd'hui en cours.
        String passee = createCampaign(admin, "Principale",
                today.minusMonths(10), today.minusMonths(4));
        String courante = createCampaign(admin, "Intermédiaire",
                today.minusMonths(3), today.plusMonths(2));

        String parcelId = createParcel(admin);

        // Saisie aujourd'hui, mais récoltée pendant la principale.
        String rattachement = harvestOn(admin, parcelId, today.minusMonths(7));
        org.junit.jupiter.api.Assertions.assertEquals(
                passee, rattachement,
                "une récolte de la principale saisie aujourd'hui doit rester dans la principale");

        // Une récolte du jour reste évidemment dans la campagne du jour.
        org.junit.jupiter.api.Assertions.assertEquals(
                courante, harvestOn(admin, parcelId, today));
    }

    @Test
    void an_explicit_choice_always_wins_over_the_date() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String passee = createCampaign(admin, "Principale",
                today.minusMonths(10), today.minusMonths(4));
        createCampaign(admin, "Intermédiaire", today.minusMonths(3), today.plusMonths(2));
        String parcelId = createParcel(admin);

        // Une récolte d'aujourd'hui rattachée à la main à la campagne passée :
        // la décision humaine prime, quel que soit le réglage.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "parcelId": "%s", "campaignId": "%s", "harvestDate": "%s",
                          "freshBeansKg": 500 }
                        """.formatted(parcelId, passee, today))
                .when().post("/api/v1/harvests")
                .then().statusCode(201)
                .body("data.campaignId", equalTo(passee));
    }

    @Test
    void the_manual_mode_restores_the_current_campaign() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        createCampaign(admin, "Principale", today.minusMonths(10), today.minusMonths(4));
        String courante = createCampaign(admin, "Intermédiaire",
                today.minusMonths(3), today.plusMonths(2));
        String parcelId = createParcel(admin);

        setAssignmentMode(admin, "MANUAL");

        // Même récolte rétroactive : au choix, elle tombe dans la courante.
        org.junit.jupiter.api.Assertions.assertEquals(
                courante, harvestOn(admin, parcelId, today.minusMonths(7)));

        // Retour au défaut : la date reprend la main.
        setAssignmentMode(admin, "DATE");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                courante, harvestOn(admin, parcelId, today.minusMonths(7)));
    }

    @Test
    void a_date_outside_every_campaign_falls_back_instead_of_failing() {
        UserEntity admin = tenantAdmin();
        LocalDate today = LocalDate.now();
        String courante = createCampaign(admin, "Intermédiaire",
                today.minusMonths(3), today.plusMonths(2));
        String parcelId = createParcel(admin);

        // Une date antérieure à toute campagne connue : la saisie ne doit
        // pas être refusée, elle se rattache à la campagne courante et se
        // corrige à la main.
        org.junit.jupiter.api.Assertions.assertEquals(
                courante, harvestOn(admin, parcelId, today.minusYears(3)));
    }
}
