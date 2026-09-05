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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * La rémunération d'un délégué se négocie campagne par campagne.
 *
 * <p>Demandé le 30/08/2026. Le taux vivait sur la fiche du délégué, une
 * valeur unique valable pour toujours : le relever pour la saison en cours
 * réécrivait ce qu'on avait convenu la saison passée, et les états d'une
 * campagne close changeaient sous les yeux du comptable.</p>
 *
 * <p>Le taux de campagne s'insère en tête de la cascade existante, devant
 * le taux commun du délégué, celui de sa catégorie et celui de la
 * structure. Il ne remplace rien : une campagne sans taux convenu retombe
 * sur le taux commun, comme avant.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateCampaignMarginTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-mrg-" + TestFixtures.randomSlugSuffix(), "Coopérative Marge");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Marge";
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

    /** Le mode de rémunération de la structure : un montant par kilo. */
    private void marginPerKg(UserEntity who, int rate) {
        givenAs(who).contentType("application/json")
                .body("""
                        { "delegateMarginMode": "PER_KG", "delegateMarginRate": %d }
                        """.formatted(rate))
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private String createCampaign(UserEntity who, String label, String start, String end) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "%s", "kind": "MAIN", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 900 }
                        """.formatted(label, start, end))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createDelegateMember(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "lastName": "%s", "firstName": "Délégué", "gender": "MALE",
                          "personType": "NATURAL_PERSON", "status": "ACTIVE",
                          "collector": true }
                        """.formatted(name))
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse setMargins(
            UserEntity who, String memberId, String json) {
        return givenAs(who).contentType("application/json").body(json)
                .when().put("/api/v1/members/" + memberId + "/collector-margins")
                .then();
    }

    @Test
    void the_rate_of_one_season_does_not_bind_the_next() {
        UserEntity admin = admin();
        marginPerKg(admin, 25);
        String previous = createCampaign(admin, "Campagne 2025", "2025-09-01", "2026-02-28");
        String current = createCampaign(admin, "Campagne 2026", "2026-09-01", "2027-02-28");
        String memberId = createDelegateMember(admin, "Koné");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 25 },
                               { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(previous, current))
                .statusCode(200)
                .body("data.collectorMarginByCampaign.size()", equalTo(2));

        List<Object> rates = givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .extract().path("data.collectorMarginByCampaign.rate");
        assertThat(rates).hasSize(2);
    }

    @Test
    void a_season_without_a_rate_falls_back_on_the_common_one() {
        UserEntity admin = admin();
        marginPerKg(admin, 25);
        String negotiated = createCampaign(admin, "Campagne négociée", "2026-09-01", "2027-02-28");
        String other = createCampaign(admin, "Campagne sans accord", "2027-09-01", "2028-02-28");
        String memberId = createDelegateMember(admin, "Traoré");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(negotiated)).statusCode(200);

        // La campagne citée porte son taux, l'autre non : le taux commun
        // reprend la main sans qu'on ait à le recopier partout.
        List<String> ids = givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().extract().path("data.collectorMarginByCampaign.campaignId");
        assertThat(ids).containsExactly(negotiated);
        assertThat(ids).doesNotContain(other);
    }

    @Test
    void the_list_replaces_what_was_there() {
        UserEntity admin = admin();
        marginPerKg(admin, 25);
        String a = createCampaign(admin, "Campagne A", "2026-09-01", "2027-02-28");
        String b = createCampaign(admin, "Campagne B", "2027-09-01", "2028-02-28");
        String memberId = createDelegateMember(admin, "Yao");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 },
                               { "campaignId": "%s", "rate": 30 } ] }
                """.formatted(a, b)).statusCode(200);

        // Retirer une campagne de la liste dit qu'aucun taux n'a été
        // convenu pour elle. Un envoi partiel laisserait vivre d'anciens
        // taux que plus personne ne voit à l'écran.
        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(a)).statusCode(200)
                .body("data.collectorMarginByCampaign.size()", equalTo(1));
    }

    @Test
    void two_rates_for_the_same_season_are_refused() {
        UserEntity admin = admin();
        String campaign = createCampaign(admin, "Campagne double", "2026-09-01", "2027-02-28");
        String memberId = createDelegateMember(admin, "Bamba");

        // Sans ce refus, le taux retenu dépendrait de l'ordre de la liste.
        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 },
                               { "campaignId": "%s", "rate": 10 } ] }
                """.formatted(campaign, campaign)).statusCode(422);
    }

    @Test
    void an_unknown_season_is_refused() {
        UserEntity admin = admin();
        String memberId = createDelegateMember(admin, "Diallo");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(java.util.UUID.randomUUID())).statusCode(404);
    }

    @Test
    void a_negative_rate_is_refused() {
        UserEntity admin = admin();
        String campaign = createCampaign(admin, "Campagne négative", "2026-09-01", "2027-02-28");
        String memberId = createDelegateMember(admin, "Ouattara");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": -5 } ] }
                """.formatted(campaign)).statusCode(400);
    }

    @Test
    void a_member_who_is_not_a_delegate_has_no_margin_to_set() {
        UserEntity admin = admin();
        String campaign = createCampaign(admin, "Campagne simple", "2026-09-01", "2027-02-28");
        String memberId = givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Simple", "firstName": "Producteur",
                          "gender": "MALE", "personType": "NATURAL_PERSON",
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");

        // Poser un taux sur un producteur qui ne collecte pour personne
        // laisserait une donnée que rien ne lit, et ferait croire à une
        // rémunération qui n'existe pas.
        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(campaign)).statusCode(422);
    }

    @Test
    void the_rate_reaches_the_mirror_supplier_that_receipts_read() {
        UserEntity admin = admin();
        marginPerKg(admin, 25);
        String campaign = createCampaign(admin, "Campagne miroir", "2026-09-01", "2027-02-28");
        String memberId = createDelegateMember(admin, "Sanogo");

        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(campaign)).statusCode(200);

        // Le reçu d'achat lit le fournisseur, pas le membre : un taux resté
        // sur la fiche du membre n'aurait rémunéré personne.
        String code = givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().extract().path("data.code");
        List<Object> mirrored = givenAs(admin).when().get("/api/v1/suppliers?perPage=100")
                .then().statusCode(200)
                .extract().path("data.items.find { it.code == '" + code + "' }"
                        + ".collectorMarginByCampaign.rate");
        assertThat(mirrored).hasSize(1);
    }

    @Test
    void a_campaign_margin_is_always_an_amount_per_kilo() {
        UserEntity admin = admin();
        // La structure rémunère en pourcentage : le mode ne s'applique
        // pas à une marge de campagne, qui est un montant au kilo.
        givenAs(admin).contentType("application/json")
                .body("{ \"delegateMarginMode\": \"PERCENT\", \"delegateMarginRate\": 3 }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        String campaign = createCampaign(admin, "Campagne au kilo",
                LocalDate.now().minusMonths(1).toString(), LocalDate.now().plusMonths(5).toString());
        String memberId = createDelegateMember(admin, "Adou");
        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(campaign)).statusCode(200);

        // 100 kg × 40 FCFA = 4 000. Lu comme un pourcentage, la même
        // valeur aurait donné 3 % de 90 000, soit 2 700 : le barème
        // aurait cessé de se calculer et le délégué aurait été mal payé.
        assertThat(marginOnReceipt(admin, memberId, campaign, 100)).isEqualTo(4_000d);
    }

    @Test
    void a_receipt_is_paid_at_the_rate_of_its_own_season() {
        UserEntity admin = admin();
        marginPerKg(admin, 25);
        String campaign = createCampaign(admin, "Campagne du reçu",
                LocalDate.now().minusMonths(1).toString(), LocalDate.now().plusMonths(5).toString());
        String memberId = createDelegateMember(admin, "Kouassi");
        setMargins(admin, memberId, """
                { "margins": [ { "campaignId": "%s", "rate": 40 } ] }
                """.formatted(campaign)).statusCode(200);

        // 100 kg à 40 FCFA le kilo : la campagne l'emporte sur le taux
        // commun de la structure, qui aurait donné 2 500.
        assertThat(marginOnReceipt(admin, memberId, campaign, 100)).isEqualTo(4_000d);
    }

    /** Le fournisseur miroir d'un membre : c'est lui que lisent les reçus. */
    private String mirrorSupplierId(UserEntity admin, String memberId) {
        String code = givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().extract().path("data.code");
        return givenAs(admin).when().get("/api/v1/suppliers?perPage=100")
                .then().statusCode(200)
                .extract().path("data.items.find { it.code == '" + code + "' }.id");
    }

    /** Enregistre un reçu porté par ce délégué et rend la marge calculée. */
    private double marginOnReceipt(UserEntity admin, String delegateMemberId,
                                   String campaignId, int weightKg) {
        fundCashBox(admin, 50_000_000);
        String producerId = givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Producteur", "firstName": "Test",
                          "gender": "MALE", "personType": "NATURAL_PERSON",
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String delegateSupplierId = mirrorSupplierId(admin, delegateMemberId);
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");

        Number margin = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "campaignId": "%s", "delegateSupplierId": "%s",
                          "weightKg": %d, "guaranteedPricePerKg": 900,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), producerId, articleId, siteId,
                        campaignId, delegateSupplierId, weightKg))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases")
                .then().statusCode(201).extract().path("data.delegateMargin");
        return margin.doubleValue();
    }
}
