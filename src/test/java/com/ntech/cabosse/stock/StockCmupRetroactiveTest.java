package com.ntech.cabosse.stock;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La valorisation CMUP ne dépend que des dates d'effet, jamais de
 * l'ordre de saisie : une écriture rétroactive rejoue la chronologie
 * du couple (article, site) et réécrit les instantanés du journal.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockCmupRetroactiveTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-retro-" + TestFixtures.randomSlugSuffix(), "Coopérative Rétroactif");
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

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin, String name) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private void opening(UserEntity admin, String siteId, String articleId,
                         int qty, int unitPrice, Instant occurredAt) {
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "siteId": "%s", "occurredAt": "%s",
                          "lines": [ { "articleId": "%s", "quantity": %d, "unitPrice": %d } ] }
                        """.formatted(siteId, occurredAt, articleId, qty, unitPrice))
                .when().post("/api/v1/stocks/opening")
                .then().statusCode(201);
    }

    private void movement(UserEntity admin, String articleId, String siteId,
                          String kind, String quantity, String unitPrice, Instant occurredAt) {
        StringBuilder body = new StringBuilder("{ \"articleId\": \"").append(articleId)
                .append("\", \"siteId\": \"").append(siteId)
                .append("\", \"kind\": \"").append(kind)
                .append("\", \"quantity\": ").append(quantity);
        if (unitPrice != null) body.append(", \"unitPrice\": ").append(unitPrice);
        if (occurredAt != null) body.append(", \"occurredAt\": \"").append(occurredAt).append("\"");
        if ("ADJUSTMENT".equals(kind)) body.append(", \"reason\": \"test\"");
        body.append(" }");
        givenAs(admin)
                .contentType("application/json")
                .body(body.toString())
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(201);
    }

    private JsonPath stockOf(UserEntity admin, String articleId, String siteId) {
        return givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private List<Map<String, Object>> movementsOf(UserEntity admin, String articleId, String siteId) {
        return givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId
                        + "/movements?limit=100")
                .then().statusCode(200)
                .extract().jsonPath().getList("data");
    }

    private static float f(Object v) {
        return v == null ? Float.NaN : Float.parseFloat(v.toString());
    }

    @Test
    void saisie_retroactive_rejoue_la_chronologie() {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Fèves rétro");
        String siteId = createSite(admin, "Entrepôt rejeu");
        Instant t0 = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant t1 = Instant.now().minus(2, ChronoUnit.DAYS);

        // t0 : amorçage 100 kg à 500. Aujourd'hui : sortie de 60 kg.
        opening(admin, siteId, articleId, 100, 500, t0);
        movement(admin, articleId, siteId, "OUT", "60", null, null);

        // Puis arrive, EN RETARD, une entrée datée t1 : 50 kg à 800.
        // Chronologie vraie : 100@500 → +50@800 (CMUP 600) → −60.
        // À l'ordre d'arrivée le CMUP aurait été (40×500+50×800)/90 ≈ 666,67.
        movement(admin, articleId, siteId, "IN", "50", "800", t1);

        JsonPath stock = stockOf(admin, articleId, siteId);
        assertEquals(90f, f(stock.get("data.quantity")), 0.0001f);
        assertEquals(600f, f(stock.get("data.cmup")), 0.0001f);

        // Les instantanés du journal sont réécrits dans l'ordre des dates :
        // la sortie est désormais valorisée au CMUP chronologique (600).
        List<Map<String, Object>> journal = movementsOf(admin, articleId, siteId);
        assertEquals(3, journal.size());
        Map<String, Object> out = journal.stream()
                .filter(m -> "OUT".equals(m.get("kind"))).findFirst().orElseThrow();
        assertEquals(600f, f(out.get("unitPrice")), 0.0001f);
        assertEquals(600f, f(out.get("cmupAfter")), 0.0001f);
        assertEquals(90f, f(out.get("quantityAfter")), 0.0001f);
        assertEquals(36000f, f(out.get("total")), 0.01f);
        Map<String, Object> retroIn = journal.stream()
                .filter(m -> "IN".equals(m.get("kind"))).findFirst().orElseThrow();
        assertEquals(150f, f(retroIn.get("quantityAfter")), 0.0001f);
        assertEquals(600f, f(retroIn.get("cmupAfter")), 0.0001f);
    }

    @Test
    void l_ordre_de_saisie_ne_change_pas_la_valorisation() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin, "Entrepôt déterminisme");
        Instant t0 = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant t1 = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.DAYS);

        // Article A : saisie dans l'ordre chronologique.
        String a = createArticle(admin, "Article ordre chrono");
        opening(admin, siteId, a, 100, 500, t0);
        movement(admin, a, siteId, "IN", "50", "800", t1);
        movement(admin, a, siteId, "OUT", "60", null, t2);

        // Article B : mêmes mouvements, l'entrée saisie en dernier.
        String b = createArticle(admin, "Article ordre inverse");
        opening(admin, siteId, b, 100, 500, t0);
        movement(admin, b, siteId, "OUT", "60", null, t2);
        movement(admin, b, siteId, "IN", "50", "800", t1);

        JsonPath sa = stockOf(admin, a, siteId);
        JsonPath sb = stockOf(admin, b, siteId);
        assertEquals(f(sa.get("data.quantity")), f(sb.get("data.quantity")), 0.0001f);
        assertEquals(f(sa.get("data.cmup")), f(sb.get("data.cmup")), 0.0001f);
        assertEquals(600f, f(sb.get("data.cmup")), 0.0001f);
    }

    @Test
    void saisies_concurrentes_convergent_vers_le_rejeu_chronologique() throws Exception {
        UserEntity admin = tenantAdmin();
        String articleId = createArticle(admin, "Fèves concurrence");
        String siteId = createSite(admin, "Entrepôt concurrence");
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        opening(admin, siteId, articleId, 100, 500, base);

        // Six entrées à des dates d'effet passées distinctes, postées en
        // parallèle dans un ordre mélangé : la plupart sont rétroactives
        // les unes par rapport aux autres, les rejeux se chevauchent.
        record Entry(int day, String qty, String pu) {}
        List<Entry> entries = new ArrayList<>(List.of(
                new Entry(9, "30", "700"),
                new Entry(8, "20", "650"),
                new Entry(7, "45", "820"),
                new Entry(6, "15", "910"),
                new Entry(5, "60", "560"),
                new Entry(4, "25", "1005")
        ));
        Collections.shuffle(entries);

        ExecutorService pool = Executors.newFixedThreadPool(entries.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Entry e : entries) {
            futures.add(pool.submit(() -> {
                start.await();
                movement(admin, articleId, siteId, "IN", e.qty(), e.pu(),
                        Instant.now().minus(e.day(), ChronoUnit.DAYS));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> fut : futures) fut.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // Rejeu de référence côté test : même formule, même arrondi
        // (HALF_EVEN, 4 décimales — miroir du $round Mongo).
        entries.sort(java.util.Comparator.comparingInt(Entry::day).reversed());
        BigDecimal qty = new BigDecimal(100);
        BigDecimal cmup = new BigDecimal(500);
        for (Entry e : entries) {
            BigDecimal q = new BigDecimal(e.qty());
            BigDecimal p = new BigDecimal(e.pu());
            BigDecimal newQty = qty.add(q);
            cmup = qty.multiply(cmup).add(q.multiply(p))
                    .divide(newQty, 4, RoundingMode.HALF_EVEN);
            qty = newQty;
        }

        JsonPath stock = stockOf(admin, articleId, siteId);
        assertEquals(qty.floatValue(), f(stock.get("data.quantity")), 0.0001f);
        assertEquals(cmup.floatValue(), f(stock.get("data.cmup")), 0.001f);

        // Le dernier instantané chronologique du journal porte le même état
        // que la position agrégée.
        List<Map<String, Object>> journal = movementsOf(admin, articleId, siteId);
        assertEquals(7, journal.size());
        Map<String, Object> latest = journal.get(0); // tri occurredAt desc
        assertEquals(qty.floatValue(), f(latest.get("quantityAfter")), 0.0001f);
        assertEquals(cmup.floatValue(), f(latest.get("cmupAfter")), 0.001f);
    }
}
