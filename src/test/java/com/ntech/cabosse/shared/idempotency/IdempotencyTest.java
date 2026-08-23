package com.ntech.cabosse.shared.idempotency;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Rejouer une écriture ne doit jamais créer de doublon.
 *
 * <p>Le cas réel : un délégué enregistre un reçu depuis une zone à réseau
 * faible, la requête part, la réponse n'arrive pas. Il ne sait pas si son
 * reçu existe. Sans clé d'idempotence, il choisit entre le doublon et la
 * perte. Avec, il rejoue et retrouve exactement sa première réponse.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class IdempotencyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity admin;

    @BeforeEach
    void setUp() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-idem-" + TestFixtures.randomSlugSuffix(), "Coopérative Idempotence");
        migrations.runMigrationsFor(tenant.databaseName);

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
    }

    private static String articleBody(String name) {
        return "{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}";
    }

    private long articleCount() {
        return givenAs(admin).when().get("/api/v1/articles?page=0&perPage=100")
                .then().statusCode(200)
                .extract().jsonPath().getLong("data.total");
    }

    @Test
    void le_rejeu_renvoie_la_reponse_d_origine_sans_creer_de_doublon() {
        String key = UUID.randomUUID().toString();
        String body = articleBody("Fèves de cacao");

        String firstId = givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body(body)
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .extract().path("data.id");

        // Même clé, même contenu : la réponse d'origine est rendue telle
        // quelle. C'est ce qui permet à la file de clore son entrée sans
        // avoir à deviner ce qui s'est passé.
        String replayedId = givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body(body)
                .when().post("/api/v1/articles")
                .then().statusCode(201)
                .body("data.id", notNullValue())
                .extract().path("data.id");

        assertEquals(firstId, replayedId, "Le rejeu doit rendre la même référence");
        assertEquals(1, articleCount(), "Le rejeu ne doit pas créer un second article");
    }

    @Test
    void sans_cle_deux_envois_creent_bien_deux_enregistrements() {
        // Contrôle négatif : sans clé, rien ne rapproche deux appels, et
        // c'est bien le comportement attendu hors rejeu.
        givenAs(admin).contentType("application/json").body(articleBody("Beurre de karité"))
                .when().post("/api/v1/articles").then().statusCode(201);
        givenAs(admin).contentType("application/json").body(articleBody("Beurre de cacao"))
                .when().post("/api/v1/articles").then().statusCode(201);
        assertEquals(2, articleCount());
    }

    @Test
    void une_cle_reutilisee_pour_autre_chose_est_refusee() {
        String key = UUID.randomUUID().toString();
        givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body(articleBody("Cacao marchand"))
                .when().post("/api/v1/articles").then().statusCode(201);

        // Rendre la réponse du premier article pour une demande qui en
        // décrit un autre confirmerait une opération qui n'a pas eu lieu.
        givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body(articleBody("Tout autre article"))
                .when().post("/api/v1/articles")
                .then().statusCode(422)
                .body("errorCode", equalTo("IDEMPOTENCY_PAYLOAD_MISMATCH"))
                .body("retryable", equalTo(false));

        assertEquals(1, articleCount());
    }

    @Test
    void une_cle_liberee_par_un_echec_reste_utilisable() {
        String key = UUID.randomUUID().toString();
        // Article sans nom : refusé. La clé ne doit pas être condamnée,
        // l'utilisateur corrige sa saisie et renvoie la même opération.
        givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body("{\"type\":\"RAW_MATERIAL\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        equalTo(400), equalTo(422)));

        givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, key)
                .body(articleBody("Corrigé après refus"))
                .when().post("/api/v1/articles")
                .then().statusCode(201);
        assertEquals(1, articleCount());
    }

    @Test
    void deux_cles_differentes_creent_deux_enregistrements() {
        String a = givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, UUID.randomUUID().toString())
                .body(articleBody("Premier article")).when().post("/api/v1/articles")
                .then().statusCode(201).extract().path("data.id");
        String b = givenAs(admin).contentType("application/json")
                .header(IdempotencyFilter.HEADER, UUID.randomUUID().toString())
                .body(articleBody("Second article")).when().post("/api/v1/articles")
                .then().statusCode(201).extract().path("data.id");
        assertNotEquals(a, b, "Deux clés distinctes sont deux opérations distinctes");
        assertEquals(2, articleCount());
    }

    @Test
    void la_lecture_ignore_la_cle() {
        // Une clé posée sur un GET n'a pas de sens et ne doit rien changer.
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            givenAs(admin).header(IdempotencyFilter.HEADER, key)
                    .when().get("/api/v1/articles?page=0&perPage=5")
                    .then().statusCode(200);
        }
    }

    @Test
    void le_refus_metier_porte_desormais_un_code_exploitable() {
        // La file de rejeu a besoin de distinguer un refus définitif d'un
        // incident passager : c'est ce que porte errorCode/retryable.
        String siteCode = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body(articleBody("Fèves sous contrainte"))
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "articleId": "%s", "siteId": "%s", "kind": "OUT", "quantity": 5 }
                        """.formatted(articleId, siteId))
                .when().post("/api/v1/stocks/movements")
                .then().statusCode(422)
                .body("errorCode", equalTo("STOCK_INSUFFICIENT"))
                .body("retryable", equalTo(false));
    }

    @Test
    void une_reponse_reussie_ne_porte_aucun_code_d_erreur() {
        // Les champs d'échec disparaissent du JSON en cas de succès : ils
        // n'ont rien à dire, et les exposer à null bruiterait chaque réponse.
        String raw = givenAs(admin)
                .when().get("/api/v1/articles?page=0&perPage=5")
                .then().statusCode(200)
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(raw.contains("\"errorCode\""),
                "Une réponse réussie ne doit pas porter de code d'erreur");
        org.junit.jupiter.api.Assertions.assertFalse(raw.contains("\"retryable\""),
                "Une réponse réussie ne doit pas porter d'indicateur de rejeu");
    }
}
