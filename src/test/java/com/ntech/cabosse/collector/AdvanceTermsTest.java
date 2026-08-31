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
import static org.hamcrest.Matchers.containsString;

/**
 * La formule de l'avance, dans les deux sens, et le contrôle du solde
 * avant d'en accorder une nouvelle.
 *
 * <p>La formule de l'expert : le prix barème d'un délégué est le prix bord
 * champ de la campagne plus sa marge de fonctionnement. Un volume s'y
 * multiplie pour donner le montant à avancer ; un montant s'y divise pour
 * donner le volume à livrer. Seul le premier sens existait, alors que le
 * second est le cas courant : le délégué demande une somme, pas un
 * tonnage.</p>
 *
 * <p>L'encours de la campagne en cours s'affiche aussi. C'est un constat,
 * jamais une garde : accorder ou refuser une avance à un délégué qui
 * traîne un solde est une décision de la gouvernance. Le logiciel montre
 * ce qu'il faut pour la prendre, et s'arrête là.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceTermsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-frm-" + TestFixtures.randomSlugSuffix(), "Coopérative Formule");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Formule";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 500_000_000);
        return u;
    }

    private void preferences(UserEntity who, String json) {
        givenAs(who).contentType("application/json").body(json)
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private String openCampaign(UserEntity who, int basePricePerKg) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKgFcfa": %d }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5),
                        basePricePerKg))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private String delegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse requestAdvance(
            UserEntity who, String delegateId, String campaignId, long amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CHEQUE",
                          "campaignId": "%s" }
                        """.formatted(delegateId, LocalDate.now(), amount, campaignId))
                .when().post("/api/v1/collector-advances").then();
    }

    private void approveAndDisburse(UserEntity who, String id) {
        givenAs(who).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(who).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
    }

    // ─── La formule, dans les deux sens ─────────────────────────────

    @Test
    void a_volume_gives_the_amount_to_advance() {
        UserEntity admin = admin();
        preferences(admin, "{ \"delegateMarginMode\": \"PER_KG\", \"delegateMarginRate\": 25 }");
        String campaign = openCampaign(admin, 900);
        String delegateId = delegate(admin, "Délégué Volume");

        // 100 kg × (900 bord champ + 25 de marge) = 92 500.
        Number amount = givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + campaign + "&volumeKg=100")
                .then().statusCode(200)
                .extract().path("data.suggestedAdvanceFcfa");
        assertThat(amount.doubleValue()).isEqualTo(92_500d);
    }

    @Test
    void an_amount_gives_the_volume_to_deliver() {
        UserEntity admin = admin();
        preferences(admin, "{ \"delegateMarginMode\": \"PER_KG\", \"delegateMarginRate\": 25 }");
        String campaign = openCampaign(admin, 900);
        String delegateId = delegate(admin, "Délégué Montant");

        // C'est le sens du terrain : le délégué demande 92 500, on en
        // déduit les 100 kg qu'il devra livrer.
        Number volume = givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + campaign + "&amountFcfa=92500")
                .then().statusCode(200)
                .extract().path("data.suggestedVolumeKg");
        assertThat(volume.doubleValue()).isEqualTo(100d);
    }

    @Test
    void without_a_per_kilo_margin_neither_direction_is_computed() {
        UserEntity admin = admin();
        // Un pourcentage ne s'ajoute pas à un prix unitaire : afficher une
        // somme fausse serait pire que de ne rien afficher.
        preferences(admin, "{ \"delegateMarginMode\": \"PERCENT\", \"delegateMarginRate\": 3 }");
        String campaign = openCampaign(admin, 900);
        String delegateId = delegate(admin, "Délégué Pourcentage");

        givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + campaign + "&amountFcfa=92500&volumeKg=100")
                .then().statusCode(200)
                .body("data.suggestedVolumeKg", org.hamcrest.Matchers.nullValue())
                .body("data.suggestedAdvanceFcfa", org.hamcrest.Matchers.nullValue());
    }

    // ─── Le contrôle avant nouvelle avance ──────────────────────────

    @Test
    void the_outstanding_of_the_current_season_is_visible() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin, 900);
        String delegateId = delegate(admin, "Délégué Encours");
        approveAndDisburse(admin,
                requestAdvance(admin, delegateId, campaign, 1_000_000).statusCode(201)
                        .extract().path("data.id"));

        Number outstanding = givenAs(admin).when()
                .get("/api/v1/collector-advances/delegates/" + delegateId
                        + "/terms?campaignId=" + campaign)
                .then().statusCode(200).extract().path("data.outstandingFcfa");
        assertThat(outstanding.doubleValue()).isEqualTo(1_000_000d);
    }

    @Test
    void a_delegate_who_owes_can_still_be_financed() {
        UserEntity admin = admin();
        String campaign = openCampaign(admin, 900);
        String delegateId = delegate(admin, "Délégué Encore Financé");

        approveAndDisburse(admin,
                requestAdvance(admin, delegateId, campaign, 5_000_000).statusCode(201)
                        .extract().path("data.id"));

        // Le logiciel n'arbitre pas : refinancer un délégué qui traîne un
        // encours est une décision de la gouvernance, et un blocage
        // automatique se substituerait à elle. L'encours est montré, la
        // décision reste humaine.
        requestAdvance(admin, delegateId, campaign, 5_000_000).statusCode(201);
    }
}
