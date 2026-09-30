package com.ntech.cabosse.commodity;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * La commission de collecte au décompte du client
 * (arbitré par l'expert-comptable le 29/09/2026).
 *
 * <p>En mandat, le décompte que le client renvoie n'est pas une vente :
 * il rembourse ce que la structure a avancé aux producteurs et lui
 * laisse sa commission. Cette commission est le seul produit de la
 * structure sur la collecte, et le seul montant imposable ; le reste
 * appartient aux producteurs et n'a jamais été à elle.</p>
 *
 * <p>Le taux est un montant par kilo facturé, convenu avec chaque client
 * et pour une campagne donnée : une saison se négocie, et deux clients
 * n'ont pas le même accord.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectionCommissionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-comm-" + TestFixtures.randomSlugSuffix(), "Coopérative Commission");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Le décompte en gros relève du négoce : sans cette activité, la
        // route n'existe pas pour ce tenant.
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Commission";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    private record Refs(String customerId, String articleId, String siteId, String campaignId) {}

    private Refs referentials(UserEntity a) {
        String customerId = givenAs(a).contentType("application/json")
                .body("{\"name\":\"Exportateur du Port\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(a).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(a).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\","
                        + "\"sellable\":true,\"purchasable\":true}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        int year = LocalDate.now().getYear();
        String campaignId = givenAs(a).contentType("application/json")
                .body("""
                        { "label": "Campagne %d", "startDate": "%d-01-01",
                          "endDate": "%d-12-31", "basePricePerKg": 1000 }
                        """.formatted(year, year, year))
                .when().post("/api/v1/campaigns").then().statusCode(201).extract().path("data.id");
        return new Refs(customerId, articleId, siteId, campaignId);
    }

    private void setPrefs(UserEntity who, String body) {
        givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private static String accounts() {
        return "data.items.find { it.sourceType == 'COMMODITY_SALE' }.entries.syscohadaAccount";
    }

    private static String amountOf(String account) {
        return "data.items.find { it.sourceType == 'COMMODITY_SALE' }.entries"
                + ".find { it.syscohadaAccount == '" + account + "' }.credit";
    }

    @Test
    void la_commission_convenue_se_pose_par_campagne() {
        UserEntity a = admin();
        Refs refs = referentials(a);

        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 25 } ] }
                        """.formatted(refs.campaignId()))
                .when().put("/api/v1/customers/" + refs.customerId() + "/collection-commissions")
                .then().statusCode(200)
                .body("data.commissionByCampaign", org.hamcrest.Matchers.hasSize(1));

        // Relue sur la fiche : un accord qu'on ne peut pas revoir ne se
        // vérifie pas au moment de contester un décompte.
        givenAs(a).queryParam("q", "Exportateur").when().get("/api/v1/customers")
                .then().statusCode(200)
                .body("data.items[0].commissionByCampaign[0].rate", equalTo(25));
    }

    @Test
    void en_mandat_le_decompte_separe_la_commission_du_remboursement() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        setPrefs(a, "{ \"collectionOnBehalf\": true }");
        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 25 } ] }
                        """.formatted(refs.campaignId()))
                .when().put("/api/v1/customers/" + refs.customerId() + "/collection-commissions")
                .then().statusCode(200);

        String ref = sale(a, refs, 1000, 1000);

        // 1 000 kg à 1 000 : 1 000 000 facturés, dont 25 000 de
        // commission et 975 000 qui remboursent les avances. Aucun
        // produit de vente : c'est toute la demande.
        givenAs(a).when().get("/api/v1/accounting/journal?search=" + ref)
                .then().statusCode(200)
                // Pas de compte de vente : la commission, elle, est bien
                // un produit, et c'est le seul.
                .body(accounts(), not(hasItem(startsWith("701"))))
                .body(amountOf("706100"), equalTo(25000))
                .body(amountOf("471100"), equalTo(975000));
    }

    @Test
    void sans_le_mode_le_decompte_reste_une_vente() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 25 } ] }
                        """.formatted(refs.campaignId()))
                .when().put("/api/v1/customers/" + refs.customerId() + "/collection-commissions")
                .then().statusCode(200);

        // Un taux convenu ne suffit pas : hors mandat la structure vend
        // pour son compte, et son produit est le prix de vente entier.
        String ref = sale(a, refs, 1000, 1000);
        givenAs(a).when().get("/api/v1/accounting/journal?search=" + ref)
                .then().statusCode(200)
                // Un compte de vente, 701 ou 702 selon la nature de
                // l'article, et aucune commission.
                .body(accounts(), hasItem(startsWith("70")))
                .body(accounts(), not(hasItem("706100")));
    }

    @Test
    void sans_taux_convenu_rien_n_est_facture_en_commission() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        setPrefs(a, "{ \"collectionOnBehalf\": true }");

        // Aucun accord pour cette campagne : le décompte rembourse tout,
        // la structure ne se paie pas sur un taux qu'elle n'a pas négocié.
        String ref = sale(a, refs, 1000, 1000);
        givenAs(a).when().get("/api/v1/accounting/journal?search=" + ref)
                .then().statusCode(200)
                .body(accounts(), not(hasItem("706100")))
                .body(amountOf("471100"), equalTo(1000000));
    }

    /** Un décompte : le stock est amorcé puis expédié au client. */
    private String sale(UserEntity a, Refs refs, int kg, int pricePerKg) {
        givenAs(a).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": %d, "unitPrice": 900 } ] }
                        """.formatted(refs.siteId(),
                        Instant.now().minus(java.time.Duration.ofDays(1)), refs.articleId(), kg))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        return givenAs(a).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "campaignId": "%s", "pricePerKg": %d,
                          "weights": { "declaredKg": %d, "dischargedKg": %d, "acceptedKg": %d } }
                        """.formatted(LocalDate.now(), refs.customerId(), refs.articleId(),
                                refs.siteId(), refs.campaignId(), pricePerKg, kg, kg, kg))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201)
                .extract().path("data.ref");
    }
}
