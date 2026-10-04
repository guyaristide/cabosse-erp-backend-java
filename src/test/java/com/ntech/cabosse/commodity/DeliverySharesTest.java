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
import static org.hamcrest.Matchers.hasSize;
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
class DeliverySharesTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-share-" + TestFixtures.randomSlugSuffix(), "Coopérative Commission");
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

    /** La matière en magasin. L'amorçage ne se fait qu'une fois par article. */
    private void stock(UserEntity a, Refs refs, int kg) {
        givenAs(a).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": %d, "unitPrice": 900 } ] }
                        """.formatted(refs.siteId(),
                        Instant.now().minus(java.time.Duration.ofDays(1)), refs.articleId(), kg))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);
    }

    /** Une expédition chez un client donné, avec son label. */
    private void sale(UserEntity a, Refs refs, String customerId, int kg, String label) {
        givenAs(a).contentType("application/json")
                .body("""
                        { "date": "%s", "customerId": "%s", "articleId": "%s", "siteId": "%s",
                          "campaignId": "%s", "pricePerKg": 1000,
                          "logistics": { "label": "%s" },
                          "weights": { "declaredKg": %d, "dischargedKg": %d, "acceptedKg": %d } }
                        """.formatted(LocalDate.now(), customerId, refs.articleId(),
                                refs.siteId(), refs.campaignId(), label, kg, kg, kg))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/commodity/sales").then().statusCode(201);
    }

    private String customer(UserEntity a, String name) {
        return givenAs(a).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void chaque_client_porte_sa_part_du_volume() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        String jb = customer(a, "JBCOCOA");
        String icp = customer(a, "ICP");
        stock(a, refs, 10000);

        sale(a, refs, jb, 7500, "RA");
        sale(a, refs, icp, 2500, "RA");

        // Le conseil lit d'abord qui a pris le plus : le tableau sort
        // trié, et les parts tombent sur cent.
        givenAs(a).when().get("/api/v1/commodity/sales/shares")
                .then().statusCode(200)
                .body("data.totalWeight", equalTo(10000))
                .body("data.byCustomer[0].label", equalTo("JBCOCOA"))
                .body("data.byCustomer[0].share", equalTo(75.00f))
                .body("data.byCustomer[1].label", equalTo("ICP"))
                .body("data.byCustomer[1].share", equalTo(25.00f));
    }

    @Test
    void le_meme_volume_se_lit_aussi_par_label() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        String jb = customer(a, "JBCOCOA");

        stock(a, refs, 10000);
        sale(a, refs, jb, 6000, "Rainforest Alliance");
        sale(a, refs, jb, 4000, "Ordinaire");

        // Deux lectures d'un même volume : les totaux ne peuvent pas
        // diverger, ils sortent du même passage.
        givenAs(a).when().get("/api/v1/commodity/sales/shares")
                .then().statusCode(200)
                .body("data.byLabel[0].label", equalTo("Rainforest Alliance"))
                .body("data.byLabel[0].share", equalTo(60.00f))
                .body("data.byLabel[1].share", equalTo(40.00f))
                .body("data.byCustomer", hasSize(1));
    }

    @Test
    void une_campagne_demandee_borne_le_tableau() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        String jb = customer(a, "JBCOCOA");
        stock(a, refs, 3000);
        sale(a, refs, jb, 3000, "RA");

        // La campagne du test porte tout : la demander ne change rien,
        // en demander une autre vide le tableau plutôt que de tout
        // montrer.
        givenAs(a).when().get("/api/v1/commodity/sales/shares?campaignId=" + refs.campaignId())
                .then().statusCode(200).body("data.totalWeight", equalTo(3000));

        givenAs(a).when()
                .get("/api/v1/commodity/sales/shares?campaignId=" + java.util.UUID.randomUUID())
                .then().statusCode(200)
                .body("data.totalWeight", equalTo(0))
                .body("data.byCustomer", hasSize(0));
    }

    @Test
    void une_expedition_sans_label_reste_comptee() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        String jb = customer(a, "JBCOCOA");

        stock(a, refs, 10000);
        sale(a, refs, jb, 5000, "RA");
        sale(a, refs, jb, 5000, "");

        // La faire disparaître ferait mentir le total : elle apparaît
        // sous une entrée sans nom.
        givenAs(a).when().get("/api/v1/commodity/sales/shares")
                .then().statusCode(200)
                .body("data.totalWeight", equalTo(10000))
                .body("data.byLabel", hasSize(2));
    }
}
