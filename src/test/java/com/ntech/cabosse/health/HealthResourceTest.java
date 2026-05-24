package com.ntech.cabosse.health;

import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Test fumée de la Phase A. Vérifie que :
 * <ul>
 *   <li>l'application démarre (sinon Quarkus échoue à instancier le test),</li>
 *   <li>le container Mongo replica set démarre et est connectable,</li>
 *   <li>{@link com.ntech.cabosse.shared.startup.ConstantsConsistencyCheck}
 *       n'a rien trouvé d'incohérent (sinon l'app refuse de démarrer),</li>
 *   <li>l'enveloppe {@code ApiResponse} est correctement sérialisée :
 *       {@code statusCode: 200, statusMessage: "OK", data: "OK"}.</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class HealthResourceTest {

    @Test
    void shouldRespondToPing() {
        given()
            .when().get("/api/v1/health/ping")
            .then()
                .statusCode(200)
                .body("statusCode", is(200))
                .body("statusMessage", equalTo("OK"))
                .body("data", equalTo("OK"));
    }
}
