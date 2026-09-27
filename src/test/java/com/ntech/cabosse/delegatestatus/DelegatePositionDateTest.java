package com.ntech.cabosse.delegatestatus;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
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
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La date d'effet d'une position est une date, pas son texte
 * (relevé le 27/09/2026).
 *
 * <p>La migration d'amorçage l'écrivait avec {@code toString()}. À la
 * relecture, le décodeur attendait une date et trouvait une chaîne : il
 * ne rendait pas la position fautive en moins, il faisait échouer
 * <strong>tout l'appel</strong>. L'état des avances aux délégués
 * répondait 500, sans que rien n'indique lequel des documents posait
 * problème, et la panne était en production.</p>
 *
 * <p>Même forme que les montants en Decimal128 : un seul document mal
 * typé emporte la requête entière. D'où ce test sur la forme stockée
 * plutôt que sur le seul résultat affiché — c'est le type en base qui
 * décide, et lui seul se vérifie sans ambiguïté.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegatePositionDateTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject MongoClient mongoClient;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-pos-" + TestFixtures.randomSlugSuffix(), "Coopérative Positions");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Positions";
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

    private String createDelegate(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");
    }

    /**
     * La position « principale », créée si l'amorçage ne l'a pas posée.
     *
     * <p>Le référentiel est amorcé par migration, qui ne s'exécute pas
     * sur un tenant monté par les fixtures : s'appuyer dessus rendrait
     * le test dépendant d'un ordre qu'il ne contrôle pas.</p>
     */
    private String principalStatus(UserEntity admin) {
        String existing = givenAs(admin).when().get("/api/v1/delegate-statuses")
                .then().statusCode(200).extract().path("data[0].id");
        if (existing != null) return existing;
        return givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"PRINCIPAL\", \"label\": \"Délégué principal\","
                        + " \"warning\": false, \"sortOrder\": 10 }")
                .when().post("/api/v1/delegate-statuses").then().statusCode(201)
                .extract().path("data.id");
    }

    private com.mongodb.client.MongoCollection<Document> positions() {
        return mongoClient.getDatabase(tenant.databaseName)
                .getCollection("delegate_status_positions");
    }

    @Test
    void une_position_posee_a_l_ecran_se_stocke_en_date() {
        UserEntity a = admin();
        String delegateId = createDelegate(a, "Délégué Position");

        String statusId = principalStatus(a);
        givenAs(a).contentType("application/json")
                .body("""
                        { "statusId": "%s", "effectiveDate": "%s",
                          "reason": "Contrôle du type stocké" }
                        """.formatted(statusId, LocalDate.now()))
                .when().post("/api/v1/delegates/" + delegateId + "/status-positions")
                .then().statusCode(201);

        Document stored = positions()
                .find(Filters.eq("delegateSupplierId", UUID.fromString(delegateId)))
                .sort(new Document("createdAt", -1)).first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("effectiveDate"))
                .as("la date d'effet est une date, pas son texte")
                .isInstanceOf(java.util.Date.class);
    }

    @Test
    void l_etat_des_avances_se_lit_avec_une_position_posee() {
        UserEntity a = admin();
        String delegateId = createDelegate(a, "Délégué Lu");
        String statusId = principalStatus(a);
        givenAs(a).contentType("application/json")
                .body("""
                        { "statusId": "%s", "effectiveDate": "%s",
                          "reason": "Position lisible" }
                        """.formatted(statusId, LocalDate.now()))
                .when().post("/api/v1/delegates/" + delegateId + "/status-positions")
                .then().statusCode(201);

        // C'est cet appel que la date en texte faisait tomber, entier,
        // sans dire lequel des documents posait problème.
        givenAs(a).when().get("/api/v1/collector-advances/delegates/statement")
                .then().statusCode(200);
    }
}
