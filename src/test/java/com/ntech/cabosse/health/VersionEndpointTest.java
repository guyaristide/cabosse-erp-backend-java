package com.ntech.cabosse.health;

import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Savoir ce qui tourne, sans jeton et sans deviner.
 *
 * <p>Identifier le build déployé demandait jusqu'ici de sonder des routes
 * au hasard pour voir lesquelles répondaient autre chose qu'un 404. La
 * question se pose à chaque incident, et elle a fait perdre une heure le
 * jour où une migration bloquait deux tenants en silence.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class VersionEndpointTest extends AbstractIntegrationTest {

    @Test
    void the_deployed_build_answers_without_a_token() {
        given()
                .when().get("/api/v1/health/version")
                .then().statusCode(200)
                .body("data.application", equalTo("cabosse-erp"))
                // La version du projet, et non « inconnu » : c'est la preuve
                // que l'identité scellée à la compilation est bien embarquée
                // et relue. Sans ce point, la sonde répondrait poliment sans
                // rien dire d'utile.
                .body("data.version", equalTo("1.0-SNAPSHOT"))
                .body("data.commit", notNullValue())
                .body("data.branch", notNullValue())
                .body("data.builtAt", notNullValue())
                .body("data.startedAt", notNullValue());
    }

    @Test
    void the_answer_says_whether_migrations_went_through() {
        given()
                .when().get("/api/v1/health/version")
                .then().statusCode(200)
                // C'est le signal qui manquait : un tenant bloqué à une
                // migration fautive ignore toutes les livraisons suivantes.
                .body("data.migrations.failed", equalTo(0))
                .body("data.migrations.applied", greaterThanOrEqualTo(0));
    }
}
