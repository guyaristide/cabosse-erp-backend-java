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
 * Les règles de la saison, dites par le modèle.
 *
 * <p>Une saison se joue en une campagne principale puis une ou plusieurs
 * intermédiaires, chacune avec sa période et son barème. Trois règles
 * découlent de là, et aucune n'était tenue.</p>
 *
 * <p>Les périodes <strong>ne se chevauchent pas</strong> : une date
 * couverte par deux campagnes n'a pas de bonne réponse, et le
 * rattachement d'une opération retenait alors la plus récemment démarrée,
 * en silence. Une année n'a <strong>qu'une principale</strong>, mais peut
 * avoir plusieurs intermédiaires. Et la nature de la campagne est portée
 * par le modèle, pas seulement par son libellé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CampaignSeasonRulesTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-sai-" + TestFixtures.randomSlugSuffix(), "Coopérative Saison");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Une activité de production ouvre les parcelles, dont un test a
        // besoin pour poser une estimation de rendement sur une campagne.
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

    private io.restassured.response.ValidatableResponse create(
            UserEntity admin, String label, String kind, String start, String end) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "label": "%s", "kind": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 1000 }
                        """.formatted(label, kind, start, end))
                .when().post("/api/v1/campaigns").then();
    }

    @Test
    void a_season_runs_a_main_campaign_then_its_intermediate_ones() {
        UserEntity a = admin();

        // Principale de septembre à février, puis intermédiaire de mars à
        // août : deux campagnes, deux barèmes, périodes consécutives.
        create(a, "Principale 2026", "MAIN", "2026-09-01", "2027-02-28")
                .statusCode(201).body("data.kind", equalTo("MAIN"));
        create(a, "Intermédiaire 2027", "INTERMEDIATE", "2027-03-01", "2027-08-31")
                .statusCode(201).body("data.kind", equalTo("INTERMEDIATE"));

        // Plusieurs intermédiaires sont attendues.
        create(a, "Intermédiaire tardive", "INTERMEDIATE", "2027-09-01", "2027-10-31")
                .statusCode(201);
    }

    @Test
    void two_campaigns_cannot_cover_the_same_day() {
        UserEntity a = admin();
        create(a, "Principale 2026", "MAIN", "2026-09-01", "2027-02-28").statusCode(201);

        // Un jour couvert deux fois : le rattachement d'une opération
        // retiendrait la plus récemment démarrée, en silence, et la
        // collecte d'une saison irait grossir l'autre.
        create(a, "Intermédiaire qui déborde", "INTERMEDIATE", "2027-02-01", "2027-08-31")
                .statusCode(422);

        // Le lendemain de la clôture, en revanche, passe.
        create(a, "Intermédiaire 2027", "INTERMEDIATE", "2027-03-01", "2027-08-31")
                .statusCode(201);
    }

    @Test
    void an_open_ended_campaign_blocks_everything_that_follows_it() {
        UserEntity a = admin();
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Sans terme", "kind": "MAIN", "startDate": "2026-09-01",
                          "basePricePerKg": 1000 }
                        """)
                .when().post("/api/v1/campaigns").then().statusCode(201);

        // Une fin absente vaut « pour l'instant sans terme » : tout ce qui
        // démarre après tombe dedans. C'est le cas d'une campagne ouverte
        // dont la clôture n'est pas encore fixée.
        create(a, "Intermédiaire 2027", "INTERMEDIATE", "2027-03-01", "2027-08-31")
                .statusCode(422);
    }

    @Test
    void a_year_has_only_one_main_campaign() {
        UserEntity a = admin();
        create(a, "Principale 2026", "MAIN", "2026-09-01", "2026-12-31").statusCode(201);

        // Deux principales sur une année ne veulent rien dire et rendraient
        // indécidable ce qu'un état « campagne principale » doit montrer.
        create(a, "Autre principale 2026", "MAIN", "2026-01-01", "2026-06-30")
                .statusCode(422);

        // La même période en intermédiaire passe : elles sont plusieurs.
        create(a, "Intermédiaire 2026", "INTERMEDIATE", "2026-01-01", "2026-06-30")
                .statusCode(201);
    }

    @Test
    void another_year_gets_its_own_main_campaign() {
        UserEntity a = admin();
        create(a, "Principale 2026", "MAIN", "2026-09-01", "2026-12-31").statusCode(201);

        // La règle porte sur l'année, pas sur le tenant.
        create(a, "Principale 2027", "MAIN", "2027-09-01", "2027-12-31")
                .statusCode(201).body("data.campaignYear", equalTo(2027));
    }

    @Test
    void editing_a_campaign_does_not_make_it_overlap_itself() {
        UserEntity a = admin();
        String id = create(a, "Principale 2026", "MAIN", "2026-09-01", "2027-02-28")
                .statusCode(201).extract().path("data.id");

        // Se comparer à soi-même refuserait toute modification de période.
        givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Principale 2026 corrigée", "kind": "MAIN",
                          "startDate": "2026-10-01", "endDate": "2027-03-31",
                          "basePricePerKg": 1000 }
                        """)
                .when().put("/api/v1/campaigns/" + id)
                .then().statusCode(200)
                .body("data.label", equalTo("Principale 2026 corrigée"));
    }

    // ─── Retirer une campagne créée par erreur ──────────────────────

    private String createId(UserEntity a, String label, String kind, String start, String end) {
        return create(a, label, kind, start, end).statusCode(201).extract().path("data.id");
    }

    @Test
    void a_season_opened_by_mistake_can_be_removed() {
        UserEntity a = admin();
        String essai = createId(a, "Essai", "MAIN", "2026-01-01", "2027-01-01");

        // Tant qu'elle est là, elle réserve sa période : la vraie campagne
        // intermédiaire de la saison ne peut pas naître.
        create(a, "Intermédiaire 2026", "INTERMEDIATE", "2026-03-01", "2026-08-31")
                .statusCode(422);

        givenAs(a).when().delete("/api/v1/campaigns/" + essai).then().statusCode(204);

        create(a, "Intermédiaire 2026", "INTERMEDIATE", "2026-03-01", "2026-08-31")
                .statusCode(201);
    }

    @Test
    void a_season_that_saw_operations_is_history_and_stays() {
        UserEntity a = admin();
        String campagne = createId(a, "Principale 2026", "MAIN", "2026-09-01", "2027-02-28");

        // Une estimation de rendement portée par une parcelle suffit : ce
        // rattachement vit dans un sous-document, là où un balayage naïf
        // ne l'aurait pas vu.
        String memberId = givenAs(a).contentType("application/json")
                .body("{\"lastName\":\"Kouassi\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        givenAs(a).contentType("application/json")
                .body("""
                        { "memberId": "%s", "name": "Parcelle A", "surfaceHa": 2.5,
                          "gpsCenter": [-7.48, 7.01], "status": "ACTIVE",
                          "campaignYields": [ { "campaignId": "%s", "estimateKg": 1200 } ] }
                        """.formatted(memberId, campagne))
                .when().post("/api/v1/parcels").then().statusCode(201);

        givenAs(a).when().delete("/api/v1/campaigns/" + campagne)
                .then().statusCode(422)
                .body("statusMessage", org.hamcrest.Matchers.containsString("opérations"));
    }

    @Test
    void closing_the_season_that_covers_today_leaves_no_current_one() {
        UserEntity a = admin();
        LocalDate today = LocalDate.now();
        String enCours = createId(a, "En cours", "MAIN",
                today.minusMonths(1).toString(), today.plusMonths(1).toString());
        // Une autre campagne ouverte, très loin dans le temps.
        createId(a, "Bien plus tard", "MAIN", "2031-01-01", "2031-06-30");

        givenAs(a).when().get("/api/v1/campaigns/current")
                .then().statusCode(200).body("data.label", equalTo("En cours"));

        givenAs(a).when().post("/api/v1/campaigns/" + enCours + "/close").then().statusCode(200);

        // Il ne reste aucune campagne couvrant aujourd'hui : l'en-tête doit
        // se taire, et non désigner celle de 2031 comme « en cours ».
        givenAs(a).when().get("/api/v1/campaigns/current")
                .then().statusCode(200).body("data", org.hamcrest.Matchers.nullValue());
    }
}
