package com.ntech.cabosse.members;

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

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * L'import rend la main tout de suite et se suit ensuite
 * (relevé le 30/09/2026).
 *
 * <p>Un fichier de quatre mille lignes met plusieurs minutes à s'écrire,
 * et le serveur d'entrée coupe la requête bien avant. L'écran recevait
 * une erreur pendant que le traitement continuait tout seul : on
 * n'atteignait jamais le résultat, et recliquer lançait un second import
 * par-dessus le premier, les deux se disputant les mêmes codes.</p>
 *
 * <p>L'appel rend maintenant l'identifiant de la trace sans attendre, et
 * l'écran suit cette trace. Ce que ces tests tiennent : la réponse est
 * immédiate, la trace existe dès cet instant, et elle finit par dire que
 * tout est passé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportAsyncTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-async-" + TestFixtures.randomSlugSuffix(), "Coopérative Asynchrone");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Asynchrone";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 10_000_000);
        return u;
    }

    private String body(int rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= rows; i++) {
            if (i > 1) sb.append(',');
            sb.append("""
                    { "rowNumber": %d, "lastName": "NOM%d", "firstName": "Prenom%d",
                      "gender": "MALE" }
                    """.formatted(i, i, i));
        }
        return sb.append(']').toString();
    }

    @Test
    void l_import_rend_la_main_et_se_suit_sur_sa_trace() {
        UserEntity a = admin();

        // La réponse porte l'identifiant de la trace, pas le résultat :
        // c'est ce qui permet à l'écran d'aller au résultat sans attendre
        // une requête que le serveur d'entrée coupera.
        String runId = givenAs(a).contentType("application/json").body(body(12))
                .when().post("/api/v1/members/import/commit?includeWarnings=true&async=true")
                .then().statusCode(202)
                .body("data.runId", notNullValue())
                .extract().path("data.runId");

        // La trace existe dès l'instant de la réponse, en cours.
        givenAs(a).when().get("/api/v1/import-runs/" + runId)
                .then().statusCode(200)
                .body("data.rowsReceived", equalTo(12));

        // L'attente est celle d'un écran qui interroge : on relit la
        // trace jusqu'à ce qu'elle se termine, sans dépasser un délai.
        String status = null;
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            status = givenAs(a).when().get("/api/v1/import-runs/" + runId)
                    .then().statusCode(200).extract().path("data.status");
            if ("DONE".equals(status) || "FAILED".equals(status)) break;
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.assertj.core.api.Assertions.assertThat(status)
                .as("l'import se termine et le dit").isEqualTo("DONE");
        givenAs(a).when().get("/api/v1/import-runs/" + runId)
                .then().statusCode(200).body("data.rowsCreated", equalTo(12));

        givenAs(a).queryParam("perPage", 100).when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.items.name", hasItem("NOM1 Prenom1"));
    }

    @Test
    void la_voie_synchrone_reste_ouverte() {
        UserEntity a = admin();

        // Les petits fichiers n'ont rien à gagner à attendre un suivi :
        // l'ancien chemin rend le compte rendu directement, et les écrans
        // qui l'emploient n'ont pas à changer.
        givenAs(a).contentType("application/json").body(body(3))
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(3));
    }
}
