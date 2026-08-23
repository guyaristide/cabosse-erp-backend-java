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
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * Deux caisses qui sortent la même marchandise au même instant ne doivent
 * jamais pouvoir vendre plus que le stock.
 *
 * <p>La garde de disponibilité vit dans le filtre de l'update Mongo
 * ({@code quantity >= besoin}) : c'est elle qui arbitre, pas le contrôle
 * de lecture qui la précède. Un harnais multi-threads est le seul test
 * qui la sollicite vraiment ; sans lui, une régression vers un
 * read-then-act repasserait toute la suite au vert en laissant le stock
 * devenir négatif en production, précisément les jours d'affluence.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StockConcurrencyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin;
    private String siteId;
    private String articleId;

    private void setUpStore(int openingQty) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-conc-" + TestFixtures.randomSlugSuffix(), "Coopérative Concurrence");
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
        admin = u;

        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "IN",
                          "quantity": %d, "unitPriceFcfa": 500 }
                        """.formatted(articleId, siteId, openingQty))
                .when().post("/api/v1/stocks/movements").then().statusCode(201);
    }

    /** Poste une sortie et rend le statut HTTP, sans assertion dans le thread. */
    private int postOut(int qty) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "OUT", "quantity": %d }
                        """.formatted(articleId, siteId, qty))
                .when().post("/api/v1/stocks/movements")
                .then().extract().statusCode();
    }

    @Test
    void des_sorties_concurrentes_ne_depassent_jamais_le_stock() throws Exception {
        setUpStore(100);

        // 8 sorties de 20 kg partent en même temps : 160 demandés pour 100
        // en stock. Exactement 5 doivent passer, les 3 autres être
        // refusées, quel que soit l'entrelacement.
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postOut(20);
            }));
        }
        start.countDown();
        int accepted = 0;
        int refused = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(60, TimeUnit.SECONDS);
            if (status == 201) accepted++;
            else if (status == 422) refused++;
            else throw new AssertionError("Statut inattendu sous concurrence : " + status);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(5, accepted, "100 kg permettent exactement 5 sorties de 20 kg, ni plus ni moins");
        assertEquals(3, refused, "Les demandes au-delà du stock doivent être refusées, pas perdues ni servies");

        // Le stock final est zéro, pas négatif : c'est la promesse que la
        // garde atomique fait à la comptabilité matière.
        Number quantity = givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId)
                .then().statusCode(200)
                .extract().path("data.quantity");
        assertEquals(0.0, quantity.doubleValue(), 0.001,
                "Le stock ne doit jamais descendre sous zéro, même sous concurrence");

        // Le journal ne porte que ce qui a réellement été accordé : un
        // refus qui laisserait un mouvement fantôme fausserait le CMUP.
        List<Map<String, Object>> journal = givenAs(admin)
                .when().get("/api/v1/stocks/" + articleId + "/sites/" + siteId + "/movements?limit=50")
                .then().statusCode(200)
                .extract().jsonPath().getList("data");
        long outs = journal.stream().filter(m -> "OUT".equals(m.get("kind"))).count();
        long ins = journal.stream().filter(m -> "IN".equals(m.get("kind"))).count();
        assertEquals(5, outs, "Le journal doit porter exactement les 5 sorties accordées");
        assertEquals(1, ins);
    }

    @Test
    void la_derniere_unite_ne_se_vend_qu_une_fois() throws Exception {
        setUpStore(20);

        // Cas le plus serré : deux sorties qui visent chacune la totalité
        // du stock restant. Une seule peut gagner.
        int workers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postOut(20);
            }));
        }
        start.countDown();
        int accepted = 0;
        for (Future<Integer> f : futures) {
            if (f.get(60, TimeUnit.SECONDS) == 201) accepted++;
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, accepted, "La dernière unité de stock ne peut être servie qu'à un seul demandeur");
    }
}
