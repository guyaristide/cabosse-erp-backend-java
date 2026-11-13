package com.ntech.cabosse.staff;

import com.ntech.cabosse.tenant.service.InvitationTokenService;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * L'équipe de l'éditeur (SAAS-34) : un agent s'invite sur une adresse
 * de l'éditeur, active son compte par le lien reçu, et se désactive
 * sans jamais laisser la console sans personne.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class StaffTest extends AbstractIntegrationTest {

    private static final String STAFF = "/api/v1/admin/staff";

    @Inject InvitationTokenService invitationTokens;

    @Test
    void un_agent_s_invite_active_son_compte_et_se_desactive() {
        UserEntity admin = fixtures.createPlatformAdmin(
                "chef-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com", "Aya", "Koné");
        String email = "agent-" + TestFixtures.randomSlugSuffix() + "@cabosse-erp.com";

        // Hors des domaines de l'éditeur : refusé.
        givenAs(admin).contentType("application/json")
                .body("{ \"email\": \"x@gmail.com\", \"firstName\": \"Jean\", \"lastName\": \"Dupont\" }")
                .when().post(STAFF).then().statusCode(422);

        String id = givenAs(admin).contentType("application/json")
                .body("{ \"email\": \"%s\", \"firstName\": \"Moussa\", \"lastName\": \"Traoré\" }".formatted(email))
                .when().post(STAFF).then().statusCode(201)
                .body("data.status", equalTo("INVITED"))
                .extract().path("data.id");
        UserEntity invited = users.findByEmail(email).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotNull(invited.invitationTokenHash);
        givenAs(admin).when().get(STAFF).then().statusCode(200)
                .body("data.findAll { it.email == '" + email + "' }", hasSize(1));

        // Renvoi : un lien neuf remplace l'ancien.
        givenAs(admin).when().post(STAFF + "/" + id + "/resend-invitation").then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertNotEquals(invited.invitationTokenHash,
                users.findByEmail(email).orElseThrow().invitationTokenHash);

        // Le courrier part par le relais de la plateforme, que les tests
        // n'interceptent pas : on pose un lien connu, comme le ferait le
        // courrier, et on l'active.
        InvitationTokenService.InvitationToken known = invitationTokens.generate();
        invited = users.findByEmail(email).orElseThrow();
        invited.invitationTokenHash = known.hash();
        users.update(invited);
        String token = known.clearValue();
        given().contentType("application/json")
                .body("{ \"token\": \"%s\", \"password\": \"Motdepasse!2026\" }".formatted(token))
                .when().post("/api/v1/auth/redeem-invitation").then().statusCode(200)
                .body("data.user.roles", hasItem("PLATFORM_ADMIN"));

        // Désactivé, il ne se connecte plus ; réactivé, il revient.
        givenAs(admin).when().post(STAFF + "/" + id + "/disable").then().statusCode(200)
                .body("data.status", equalTo("DISABLED"));
        given().contentType("application/json")
                .body("{ \"email\": \"%s\", \"password\": \"Motdepasse!2026\" }".formatted(email))
                .when().post("/api/v1/auth/login").then().statusCode(401);
        givenAs(admin).when().post(STAFF + "/" + id + "/enable").then().statusCode(200)
                .body("data.status", equalTo("ACTIVE"));

        // On ne se désactive pas soi-même.
        givenAs(admin).when().post(STAFF + "/" + admin.id + "/disable").then().statusCode(422);
    }
}
