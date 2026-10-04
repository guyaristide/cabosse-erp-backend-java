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
class SaleSettlementImportTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-decompte-" + TestFixtures.randomSlugSuffix(), "Coopérative Commission");
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
    void le_modele_porte_les_colonnes_du_decompte() {
        UserEntity a = admin();

        // Le modèle est le contrat avec celui qui prépare le fichier :
        // une colonne qui n'y figure pas ne sera jamais remplie.
        String csv = givenAs(a)
                .when().get("/api/v1/commodity/sales/import/template?format=csv")
                .then().statusCode(200).extract().asString();

        for (String header : java.util.List.of(
                "Péréquation transport", "Différentiel ramassage", "Valeur totale",
                "BIC", "Timbre fiscal", "Commission commercial",
                "Remboursement mandat", "Remboursement revolving", "Mise en compte",
                "Total retenues", "Montant net")) {
            org.assertj.core.api.Assertions.assertThat(csv)
                    .as("colonne « %s » absente du modèle", header)
                    .contains(header);
        }
    }

    @Test
    void le_decompte_importe_arrive_sur_la_vente() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        stock(a, refs, 10000);

        String payload = """
                [ { "rowNumber": 1, "customerName": "Exportateur du Port",
                    "productCode": "Fèves séchées", "date": "%s",
                    "siteId": "%s", "campaignId": "%s",
                    "declaredKg": "10000", "acceptedKg": "10000",
                    "montantFacture": "10000000",
                    "transportEqualization": "250000",
                    "gatheringDifferential": "120000",
                    "totalValue": "10370000",
                    "bic": "200000", "fiscalStamp": "5000",
                    "salesCommission": "150000",
                    "mandateRepayment": "9000000",
                    "revolvingRepayment": "400000",
                    "collectorRetention": "80000",
                    "totalDeductions": "9835000",
                    "netAmount": "535000" } ]
                """.formatted(LocalDate.now(), refs.siteId(), refs.campaignId());

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/commodity/sales/import/preview")
                .then().statusCode(200).body("data.readyRows", equalTo(1));

        String ref = givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(1))
                .extract().path("data.createdRefs[0]");

        String id = givenAs(a).when().get("/api/v1/commodity/sales?q=" + ref)
                .then().statusCode(200).extract().path("data.items[0].id");

        // Les totaux sont ceux du document : les recalculer donnerait un
        // chiffre juste et différent de celui que le client paiera.
        givenAs(a).when().get("/api/v1/commodity/sales/" + id)
                .then().statusCode(200)
                .body("data.settlement.transportEqualization", equalTo(250000))
                .body("data.settlement.mandateRepayment", equalTo(9000000))
                .body("data.settlement.collectorRetention", equalTo(80000))
                .body("data.settlement.totalDeductions", equalTo(9835000))
                .body("data.settlement.netAmount", equalTo(535000));
    }

    @Test
    void une_vente_sans_decompte_reste_valable() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        stock(a, refs, 5000);

        // Le décompte arrive après l'expédition : la vente s'enregistre
        // sans lui, et ses colonnes restent vides plutôt qu'à zéro, qui
        // se lirait comme une retenue nulle constatée.
        String payload = """
                [ { "rowNumber": 1, "customerName": "Exportateur du Port",
                    "productCode": "Fèves séchées", "date": "%s",
                    "siteId": "%s", "campaignId": "%s",
                    "declaredKg": "5000", "acceptedKg": "5000",
                    "montantFacture": "5000000" } ]
                """.formatted(LocalDate.now(), refs.siteId(), refs.campaignId());

        givenAs(a).contentType("application/json").body(payload)
                .when().post("/api/v1/commodity/sales/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(1));
    }
}
