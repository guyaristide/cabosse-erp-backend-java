package com.ntech.cabosse.producerpurchase;

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

/**
 * La fiche des entrées du jour suit le carnet du magasinier (CE-185).
 *
 * <p>Ligne d'ouverture au report du stock, une ligne par reçu dans
 * l'ordre de saisie, cumuls progressifs en quantité et en sacs, totaux.
 * Les chiffres calquent l'exemple de l'expert : 3 200 kg de report, deux
 * livraisons, et le cumul qui finit là où le carnet le dit.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DayIntakeSheetTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity tenantAdmin() {
        tenant = fixtures.createActiveTenant(
                "coop-fiche-" + TestFixtures.randomSlugSuffix(), "Coopérative Fiche du jour");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
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
        fundCashBox(u, 50_000_000);
        return u;
    }

    @Test
    void the_sheet_carries_the_opening_and_running_totals() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        // Le report du carnet : 3 200 kg déjà en magasin, ancrés à la
        // veille. La photo d'ouverture se prend à minuit : un amorçage
        // daté du jour même arriverait « après » elle, et le report est
        // par nature un héritage des jours précédents.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 3200, "unitPrice": 1200 } ] }
                        """.formatted(siteId,
                        java.time.Instant.now().minus(java.time.Duration.ofDays(1)), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        String m1 = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Diarra\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String m2 = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Kobenan\",\"gender\":\"FEMALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        LocalDate today = LocalDate.now();
        // Deux livraisons du jour, dans l'ordre du carnet : 1 455 kg en 23
        // sacs, puis 193 kg en 3 sacs.
        for (String[] delivery : new String[][]{
                {m1, "23", "1455"}, {m2, "3", "193"}}) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "nbSacs": %s, "weightKg": %s,
                              "guaranteedPricePerKg": 1200, "paymentMethod": "CASH" }
                            """.formatted(today, delivery[0], articleId, siteId, delivery[1], delivery[2]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        givenAs(admin)
                .queryParam("date", today.toString())
                .queryParam("siteId", siteId)
                .when().get("/api/v1/producer-purchases/day-sheet")
                .then().statusCode(200)
                // La photo rend un décimal (3200.0) : on compare en flottant.
                .body("data.openingQuantity", equalTo(3200.0F))
                .body("data.rows", hasSize(2))
                .body("data.rows[0].cumulativeQuantity", equalTo(4655.0F))
                .body("data.rows[0].cumulativeBags", equalTo(23))
                .body("data.rows[1].cumulativeQuantity", equalTo(4848.0F))
                .body("data.rows[1].cumulativeBags", equalTo(26))
                .body("data.rows[1].supplierKind", equalTo("PRODUCER"))
                .body("data.totalWeightKg", equalTo(1648))
                .body("data.totalAmount", equalTo(1977600))
                .body("data.closingQuantity", equalTo(4848.0F));

        // L'export sort dans les trois formats, ouverture et totaux inclus.
        givenAs(admin)
                .queryParam("date", today.toString())
                .queryParam("siteId", siteId)
                .queryParam("format", "csv")
                .when().get("/api/v1/producer-purchases/day-sheet/export")
                .then().statusCode(200);

        // ── Le brassage de l'après-midi ─────────────────────────────
        // Le magasinier reprend un lot douteux, en retire les impuretés
        // et sort 3 sacs pour 484 kg. Le carnet le note en négatif, sans
        // prix ni montant, et la clôture en tient compte.
        String correctionRef = givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "date": "%s",
                          "reason": "Perte de poids pour brassage",
                          "bags": 3, "weightKg": 484 }
                        """.formatted(articleId, siteId, today))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stock-corrections")
                .then().statusCode(201)
                .body("data.value", equalTo(580800.00F))
                .extract().path("data.ref");

        givenAs(admin)
                .queryParam("date", today.toString())
                .queryParam("siteId", siteId)
                .when().get("/api/v1/producer-purchases/day-sheet")
                .then().statusCode(200)
                .body("data.rows", hasSize(3))
                // La correction vient après les entrées : on brasse ce
                // qu'on vient de recevoir.
                .body("data.rows[2].rowKind", equalTo("CORRECTION"))
                .body("data.rows[2].supplierName", equalTo("Perte de poids pour brassage"))
                .body("data.rows[2].ref", equalTo(correctionRef))
                .body("data.rows[2].nbSacs", equalTo(-3))
                .body("data.rows[2].weightKg", equalTo(-484))
                // Ni prix ni montant : rien n'a été acheté.
                .body("data.rows[2].unitPrice", org.hamcrest.Matchers.nullValue())
                .body("data.rows[2].amount", org.hamcrest.Matchers.nullValue())
                .body("data.rows[2].cumulativeQuantity", equalTo(4364.0F))
                .body("data.rows[2].cumulativeBags", equalTo(23))
                // Les entrées du jour ne bougent pas : la perte compte à part.
                .body("data.totalWeightKg", equalTo(1648))
                .body("data.totalBags", equalTo(26))
                .body("data.totalCorrectedWeightKg", equalTo(484))
                .body("data.totalCorrectedBags", equalTo(3))
                .body("data.closingQuantity", equalTo(4364.0F))
                .body("data.closingBags", equalTo(23));
    }

    /**
     * Ce qu'une correction fait au stock et au grand livre.
     *
     * <p>La matière sort pour de bon, au coût moyen du jour, et la
     * variation de stock passe en charge : c'est la même écriture qu'un
     * manquant d'inventaire, constatée plus tôt.</p>
     */
    @Test
    void la_correction_sort_la_matiere_et_passe_en_charge() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": 1000, "unitPrice": 1200 } ] }
                        """.formatted(siteId,
                        java.time.Instant.now().minus(java.time.Duration.ofDays(1)), articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "date": "%s",
                          "reason": "Perte de poids pour brassage", "bags": 1, "weightKg": 50 }
                        """.formatted(articleId, siteId, LocalDate.now()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stock-corrections")
                .then().statusCode(201)
                .body("data.unitPrice", equalTo(1200.0F))
                .body("data.value", equalTo(60000.00F))
                .body("data.pieceRef", org.hamcrest.Matchers.notNullValue());

        givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .body("data.quantity", equalTo(950))
                // Une sortie ne révise pas le coût moyen des unités restantes.
                .body("data.cmup", equalTo(1200.0F));
    }

    /**
     * Le registre de septembre du magasinier central, rejoué.
     *
     * <p>Quatre pertes de brassage y figurent, surlignées à la main sur
     * le classeur reçu : 484 kg et 3 sacs le 8, 8 kg et 1 sac le 14,
     * 59 kg et 22 sacs le 17, 36 kg et 20 sacs le 18. La colonne
     * « Stock » du registre est reproduite ici valeur par valeur ; c'est
     * elle qui dit si le produit tient le carnet.</p>
     *
     * <p>Les sacs retirés ne sont pas ceux qui portaient le poids retiré :
     * 3 sacs pour 484 kg, puis 22 sacs pour 59 kg. Les deux grandeurs
     * sont indépendantes, et le produit ne doit jamais en déduire l'une
     * de l'autre.</p>
     */
    @Test
    void le_registre_de_septembre_se_rejoue_a_l_identique() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves de cacao\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"OUEDRAOGO\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        // Le stock magasin du 4 septembre, ancré la veille : 46 572 kg.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "2026-09-03T08:00:00Z",
                          "lines": [ { "articleId": "%s", "quantity": 46572, "unitPrice": 1200 } ] }
                        """.formatted(siteId, articleId))
                .when().post("/api/v1/stocks/opening").then().statusCode(201);

        // Les entrées du registre, jour par jour, poids et sacs.
        String[][] receipts = {
                {"2026-09-04", "4", "225"}, {"2026-09-04", "2", "113"},
                {"2026-09-07", "10", "575"},
                {"2026-09-08", "2", "124"},
                {"2026-09-11", "2", "87"}, {"2026-09-11", "14", "852"},
                {"2026-09-11", "24", "1456"},
                {"2026-09-13", "3", "230"},
                {"2026-09-14", "1", "52"},
                {"2026-09-15", "152", "9125"},
                {"2026-09-16", "1", "10"},
                {"2026-09-17", "1", "40"},
                {"2026-09-18", "2", "111"}, {"2026-09-18", "1", "58"},
        };
        for (String[] r : receipts) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "nbSacs": %s, "weightKg": %s,
                              "guaranteedPricePerKg": 1200, "paymentMethod": "CASH" }
                            """.formatted(r[0], memberId, articleId, siteId, r[1], r[2]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        // Les quatre lignes surlignées du registre.
        String[][] losses = {
                {"2026-09-08", "3", "484"},
                {"2026-09-14", "1", "8"},
                {"2026-09-17", "22", "59"},
                {"2026-09-18", "20", "36"},
        };
        for (String[] l : losses) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "articleId": "%s", "siteId": "%s", "date": "%s",
                              "reason": "Perte de poids pour brassage",
                              "bags": %s, "weightKg": %s }
                            """.formatted(articleId, siteId, l[0], l[1], l[2]))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/stock-corrections").then().statusCode(201);
        }

        // La colonne « Stock » du registre, au soir de chaque journée.
        String[][] expected = {
                {"2026-09-04", "46910"}, {"2026-09-07", "47485"},
                {"2026-09-08", "47125"}, {"2026-09-11", "49520"},
                {"2026-09-13", "49750"}, {"2026-09-14", "49794"},
                {"2026-09-15", "58919"}, {"2026-09-16", "58929"},
                {"2026-09-17", "58910"}, {"2026-09-18", "59043"},
        };
        for (String[] day : expected) {
            givenAs(admin)
                    .queryParam("date", day[0])
                    .queryParam("siteId", siteId)
                    .queryParam("articleId", articleId)
                    .when().get("/api/v1/producer-purchases/day-sheet")
                    .then().statusCode(200)
                    .body("data.closingQuantity", equalTo(Float.parseFloat(day[1])));
        }

        // Le 18, deux entrées puis une perte : les sacs du jour valent
        // 3 entrés moins 20 retirés, et le poids retiré se lit à part.
        givenAs(admin)
                .queryParam("date", "2026-09-18")
                .queryParam("siteId", siteId)
                .queryParam("articleId", articleId)
                .when().get("/api/v1/producer-purchases/day-sheet")
                .then().statusCode(200)
                .body("data.rows", hasSize(3))
                .body("data.totalBags", equalTo(3))
                .body("data.totalCorrectedBags", equalTo(20))
                .body("data.totalCorrectedWeightKg", equalTo(36))
                .body("data.closingBags", equalTo(-17));
    }

    /** On ne retire pas plus que ce qui est là. */
    @Test
    void une_correction_au_dela_du_stock_est_refusee() {
        UserEntity admin = tenantAdmin();
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "date": "%s",
                          "reason": "Perte de poids pour brassage", "bags": 1, "weightKg": 50 }
                        """.formatted(articleId, siteId, LocalDate.now()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/stock-corrections")
                .then().statusCode(422);

        // Rien ne reste derrière : ni correction enregistrée, ni ligne
        // dans la fiche du jour.
        givenAs(admin)
                .queryParam("siteId", siteId)
                .when().get("/api/v1/stock-corrections")
                .then().statusCode(200)
                .body("data.items", hasSize(0));
    }
}
