package com.ntech.cabosse.test;

import com.mongodb.client.MongoClient;
import com.ntech.cabosse.auth.service.JwtIssuer;
import com.ntech.cabosse.catalog.seed.CatalogSeeder;
import com.ntech.cabosse.shared.persistence.CacheNames;
import com.ntech.cabosse.shared.storage.CloudFileRepository;
import com.ntech.cabosse.shared.storage.FileStorageConfig;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import io.quarkus.cache.CacheManager;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;

/**
 * Classe mère des tests d'intégration. Hérite à chaque IT.
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Démarrage du container MongoDB replica set via
 *       {@link MongoReplicaSetTestResource}.</li>
 *   <li>Nettoyage de l'état entre tests (DB, fichiers, mailbox, cache).</li>
 *   <li>Helpers pour générer des JWT et lancer des requêtes
 *       authentifiées.</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
public abstract class AbstractIntegrationTest {

    @Inject protected MongoClient mongoClient;
    @Inject protected JwtIssuer jwtIssuer;
    @Inject protected MockMailbox mailbox;
    @Inject protected CacheManager cacheManager;
    @Inject protected FileStorageConfig storageConfig;

    @Inject protected TenantRepository tenants;
    @Inject protected UserRepository users;
    @Inject protected CloudFileRepository cloudFiles;

    @Inject protected TestFixtures fixtures;
    @Inject protected CatalogSeeder catalogSeeder;

    @BeforeEach
    void resetState() throws IOException {
        // ── 1. Drop control plane
        mongoClient.getDatabase("cabosse_control").drop();

        // ── 2. Drop toute base tenant éventuellement créée pendant le test précédent
        mongoClient.listDatabaseNames().forEach(name -> {
            if (name.startsWith("tenant_test_")) {
                mongoClient.getDatabase(name).drop();
            }
        });

        // ── 3. Wipe le storage local
        wipeLocalStorage();

        // ── 4. Reset MockMailbox
        mailbox.clear();

        // ── 5. Invalider les caches (TenantRegistryCache notamment)
        cacheManager.getCache(CacheNames.TENANT_REGISTRY)
                .ifPresent(cache -> cache.invalidateAll().await().indefinitely());

        // ── 6. Re-seed les catalogues (countries/regions/cities/industries/plans)
        //       — droppés à l'étape 1, indispensables pour la validation FK
        //       du provisioning.
        catalogSeeder.seedAll();
    }

    /**
     * Génère un Bearer token JWT pour un utilisateur donné, comme s'il
     * s'était authentifié via {@code POST /auth/login}.
     */
    protected String bearerTokenFor(UserEntity user) {
        TenantEntity tenant = tenants.findById(user.tenantId);
        return jwtIssuer.issueAccessToken(user, tenant).token();
    }

    /**
     * Spécification RestAssured pré-configurée avec le bon Bearer token.
     * Usage : {@code givenAs(admin).when().get("/api/v1/admin/tenants").then()...}
     */
    /**
     * Garnit la caisse, comme une coopérative le fait à l'ouverture.
     *
     * <p>Une caisse ne peut jamais être négative : aucun paiement en
     * espèces ne passe tant qu'elle est vide. En exploitation, la structure
     * déclare l'argent déjà présent par une écriture d'ouverture, ou
     * l'approvisionne depuis sa banque. Ce helper fait la première, par le
     * même chemin qu'un comptable : une opération diverse, validée.</p>
     */
    protected void fundCashBox(UserEntity admin, long amount) {
        String odId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Solde d'ouverture de caisse",
                          "lines": [
                            { "account": "571000", "libelle": "Espèces en caisse",
                              "debit": %d },
                            { "account": "471000", "libelle": "Contrepartie d'amorçage",
                              "credit": %d } ] }
                        """.formatted(java.time.LocalDate.now().minusYears(1), amount, amount))
                .when().post("/api/v1/accounting/od")
                .then().statusCode(201).extract().path("data.id");
        givenAs(admin).when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);
    }

    protected RequestSpecification givenAs(UserEntity user) {
        return given().auth().oauth2(bearerTokenFor(user));
    }

    private void wipeLocalStorage() throws IOException {
        Path uploadsPath = Paths.get(storageConfig.local().basePath()).toAbsolutePath();
        if (!Files.exists(uploadsPath)) return;
        try (Stream<Path> walk = Files.walk(uploadsPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (!p.equals(uploadsPath)) Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort
                }
            });
        }
    }
}
