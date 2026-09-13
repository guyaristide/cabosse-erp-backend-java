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
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Les objectifs mensuels d'une campagne.
 *
 * <p>Demandés par la coopérative le 13/09/2026 : ses tableaux de bord
 * confrontent chaque mois le réalisé à un objectif et affichent l'écart.
 * Le réalisé se calcule déjà ; l'objectif est une décision, il ne se
 * déduit de rien.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignTargetTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-obj-" + TestFixtures.randomSlugSuffix(), "Coopérative Objectifs");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Objectifs";
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

    private String campaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKg": 1200 }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse setTarget(UserEntity who, String campaignId,
                                                                   String body) {
        return givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/campaigns/" + campaignId + "/targets").then();
    }

    @Test
    void a_target_is_set_corrected_and_cleared() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();

        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 12000, \"saleTargetKg\": 9000 }"
                        .formatted(month))
                .statusCode(200)
                .body("data.collectionTargetKg", equalTo(12000));

        // Une seconde saisie corrige la première : deux objectifs sur le
        // même mois ne voudraient plus rien dire.
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 15000 }".formatted(month))
                .statusCode(200);
        givenAs(admin).when().get("/api/v1/campaigns/" + campaignId + "/targets")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].collectionTargetKg", equalTo(15000))
                .body("data[0].saleTargetKg", nullValue());

        // Les deux montants absents effacent l'objectif : sans cela, un
        // mois vidé garderait une cible fantôme contre laquelle l'écart
        // continuerait de se calculer.
        setTarget(admin, campaignId, "{ \"month\": \"%s\" }".formatted(month)).statusCode(200);
        givenAs(admin).when().get("/api/v1/campaigns/" + campaignId + "/targets")
                .then().statusCode(200).body("data", hasSize(0));
    }

    @Test
    void a_month_outside_the_campaign_is_refused() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);

        // Un objectif posé hors campagne ne serait confronté à rien : le
        // tableau de bord ne parcourt que les mois de la campagne, et la
        // cible resterait invisible sans qu'on comprenne pourquoi.
        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 500 }"
                        .formatted(YearMonth.now().plusYears(3)))
                .statusCode(422);
    }

    @Test
    void the_dashboard_shows_the_gap_only_where_a_target_was_set() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String month = YearMonth.now().toString();

        setTarget(admin, campaignId,
                "{ \"month\": \"%s\", \"collectionTargetKg\": 10000 }".formatted(month))
                .statusCode(200);

        var response = givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/executive-dashboard/campaign")
                .then().statusCode(200);

        // Rien n'a été collecté : l'écart vaut le retard entier, et il
        // n'existe que parce qu'un objectif a été posé.
        response.body("data.months.find { it.month == '%s' }.collectionTargetKg".formatted(month),
                        equalTo(10000))
                .body("data.months.find { it.month == '%s' }.collectionGapKg".formatted(month),
                        equalTo(-10000))
                // Aucun objectif de vente : ni cible, ni écart. Un zéro se
                // lirait comme une cible décidée à zéro.
                .body("data.months.find { it.month == '%s' }.saleTargetKg".formatted(month),
                        nullValue())
                .body("data.months.find { it.month == '%s' }.saleGapKg".formatted(month),
                        nullValue());
    }
}
