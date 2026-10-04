package com.ntech.cabosse.tenant;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantSubscription;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Les licences de toutes les structures, vues d'un seul endroit.
 *
 * <p>La date de fin existait et l'état se calculait, mais rien ne les
 * montrait ensemble : savoir quelles licences arrivent à échéance
 * demandait d'ouvrir les structures une à une (demandé le
 * 04/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantLicenseBoardTest extends AbstractIntegrationTest {

    @Inject TenantRepository tenantRepo;

    private TenantEntity withLicenceEndingOn(LocalDate end) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-board-" + TestFixtures.randomSlugSuffix(), "Coopérative Licence");
        TenantSubscription sub = new TenantSubscription();
        sub.planCode = "pro";
        sub.cycle = BillingCycle.YEARLY;
        sub.periods = 1;
        sub.startDate = end.minusYears(1);
        sub.endDate = end;
        sub.amount = new BigDecimal("2000000");
        sub.label = "Licence annuelle";
        sub.activatedAt = Instant.now();
        tenant.subscription = sub;
        tenantRepo.update(tenant);
        return tenant;
    }

    /** La ligne d'une structure donnée dans l'état des licences. */
    private io.restassured.path.json.JsonPath board() {
        return givenAs(fixtures.createPlatformAdmin())
                .when().get("/api/v1/admin/tenants/licenses")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    void chaque_licence_porte_son_etat_et_son_echeance() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().plusDays(10));

        var row = board().getList("data.findAll { it.tenantId == '" + tenant.id + "' }");
        assertThat(row).hasSize(1);

        var first = board().getMap("data.find { it.tenantId == '" + tenant.id + "' }");
        assertThat(first.get("state")).isEqualTo("EXPIRING");
        assertThat(first.get("label")).isEqualTo("Licence annuelle");
        // Dix jours : le nombre sert à trier et à décider, pas seulement
        // à colorer un badge.
        assertThat(((Number) first.get("daysToExpiry")).longValue()).isEqualTo(10L);
    }

    @Test
    void une_structure_sans_licence_y_figure_quand_meme() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-essai-" + TestFixtures.randomSlugSuffix(), "Coopérative Essai");

        // La faire disparaître ferait oublier celles qu'il reste à
        // facturer : c'est précisément ce qu'on vient chercher ici.
        var row = board().getMap("data.find { it.tenantId == '" + tenant.id + "' }");
        assertThat(row.get("state")).isEqualTo("NONE");
        assertThat(row.get("endDate")).isNull();
    }

    @Test
    void l_urgence_passe_devant() {
        withLicenceEndingOn(LocalDate.now().plusYears(1));
        TenantEntity lapsed = withLicenceEndingOn(LocalDate.now().minusMonths(2));

        // L'état est trié par urgence : une liste alphabétique
        // obligerait à la parcourir pour trouver ce qui appelle une
        // décision.
        var states = board().getList("data.state", String.class);
        assertThat(states.get(0)).isEqualTo("LAPSED");
        var ids = board().getList("data.tenantId", String.class);
        assertThat(ids.get(0)).isEqualTo(lapsed.id.toString());
    }

    @Test
    void l_acces_se_coupe_et_se_rend_a_la_main() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().plusMonths(6));
        var platform = fixtures.createPlatformAdmin();

        givenAs(platform).contentType("application/json")
                .body("{\"reason\":\"Impayé de deux mois\"}")
                .when().post("/api/v1/admin/tenants/" + tenant.id + "/suspend")
                .then().statusCode(200).body("data.status", equalTo("SUSPENDED"));

        // Deux fois de suite n'a pas de sens : l'accès est déjà coupé.
        givenAs(platform).contentType("application/json").body("{}")
                .when().post("/api/v1/admin/tenants/" + tenant.id + "/suspend")
                .then().statusCode(422);

        givenAs(platform).contentType("application/json")
                .when().post("/api/v1/admin/tenants/" + tenant.id + "/reactivate")
                .then().statusCode(200).body("data.status", equalTo("ACTIVE"));

        assertThat(tenantRepo.findById(tenant.id).status).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void le_courrier_de_licence_se_renvoie_sans_reactiver() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().plusMonths(6));

        // Un courrier se perd, une adresse change : le renvoyer ne doit
        // pas obliger à refaire l'activation. Aucune adresse cochée,
        // donc rien ne part, et c'est dit.
        givenAs(fixtures.createPlatformAdmin()).contentType("application/json")
                .body("{\"notifyEmails\":[]}")
                .when().post("/api/v1/admin/tenants/" + tenant.id + "/license-mail")
                .then().statusCode(200).body("data.sent", equalTo(0));
    }

    @Test
    void on_ne_renvoie_rien_pour_une_structure_sans_licence() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-sans-" + TestFixtures.randomSlugSuffix(), "Sans licence");

        givenAs(fixtures.createPlatformAdmin()).contentType("application/json")
                .body("{\"notifyEmails\":[]}")
                .when().post("/api/v1/admin/tenants/" + tenant.id + "/license-mail")
                .then().statusCode(422);
    }
}
