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

import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * La carte du producteur est une pièce comme une autre, et c'est son
 * <em>type</em> qui dit ce qu'elle permet.
 *
 * <p>Deux usages à ne pas confondre : retrouver un producteur dans un
 * fichier importé, et établir son identité. Une carte de filière fait le
 * premier sans faire le second. Un même numéro ne peut pas désigner deux
 * producteurs, sans quoi un achat se paie à la mauvaise personne.</p>
 *
 * <p>Rien de tout cela ne présuppose une filière : une structure qui ne
 * coche aucun type ne voit ni carte ni clé, et se retrouve sur son seul
 * code interne.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ProducerCardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-carte-" + TestFixtures.randomSlugSuffix(), "Coopérative Carte");
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
        return u;
    }

    private void createType(UserEntity admin, String name,
                            boolean identityProof, boolean usableAsProducerRef) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "%s", "identityProof": %b, "usableAsProducerRef": %b }
                        """.formatted(name, identityProof, usableAsProducerRef))
                .when().post("/api/v1/id-document-types")
                .then().statusCode(201);
    }

    private String createMemberWithCard(UserEntity admin, String lastName, String cardNumber) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "%s", "gender": "MALE", "status": "ACTIVE",
                          "identityDocuments": [
                            { "type": "Carte producteur", "number": "%s" }
                          ] }
                        """.formatted(lastName, cardNumber))
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void the_same_card_number_cannot_designate_two_producers() {
        UserEntity admin = tenantAdmin();
        createType(admin, "Carte producteur", false, true);
        createMemberWithCard(admin, "Kouassi", "CCC-2021-183667");

        // Même numéro écrit autrement : espaces et casse ne font pas une
        // autre carte. Le refus nomme le porteur, sinon la correction est
        // une devinette.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Diabate", "gender": "MALE", "status": "ACTIVE",
                          "identityDocuments": [
                            { "type": "Carte producteur", "number": "ccc 2021 183667" }
                          ] }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(409)
                .body("statusMessage", containsString("Kouassi"));
    }

    @Test
    void a_card_finds_the_producer_but_does_not_prove_identity() {
        UserEntity admin = tenantAdmin();
        createType(admin, "Carte producteur", false, true);
        String memberId = createMemberWithCard(admin, "Yao", "CCC-777");

        // La pièce compte comme clé de rapprochement…
        givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.identityDocuments", org.hamcrest.Matchers.hasSize(1));

        // …mais le dossier reste signalé sans pièce d'identité.
        givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.fileStatus.missingFields",
                        org.hamcrest.Matchers.hasItem("Pièce d'identité"));
    }

    @Test
    void a_type_that_is_not_an_identifier_produces_no_key() {
        UserEntity admin = tenantAdmin();
        // Filière sans carte : seuls des types d'identité existent.
        createType(admin, "Carte nationale d'identité", true, false);

        String first = givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Bamba", "gender": "MALE", "status": "ACTIVE",
                          "identityDocuments": [
                            { "type": "Carte nationale d'identité", "number": "CI-001" }
                          ] }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201).extract().path("data.id");

        // Deux personnes peuvent partager un numéro de pièce mal saisi sans
        // que cela crée d'ambiguïté de rapprochement : aucune clé n'est
        // dérivée d'un type qui ne sert pas d'identifiant.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Traore", "gender": "MALE", "status": "ACTIVE",
                          "identityDocuments": [
                            { "type": "Carte nationale d'identité", "number": "CI-001" }
                          ] }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201);

        givenAs(admin).when().get("/api/v1/members/" + first)
                .then().statusCode(200)
                .body("data.fileStatus.missingFields",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Pièce d'identité")));
    }

    @Test
    void checking_the_identifier_flag_makes_existing_numbers_findable() {
        UserEntity admin = tenantAdmin();
        createType(admin, "Carte producteur", false, false);
        createMemberWithCard(admin, "Koffi", "CARTE-42");

        String typeId = givenAs(admin).when().get("/api/v1/id-document-types")
                .then().statusCode(200)
                .extract().path("data.find { it.name == 'Carte producteur' }.id");

        // Le type devient identifiant : les numéros déjà saisis doivent le
        // devenir aussi, sinon la case à cocher ne servirait qu'aux fiches
        // à venir.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "Carte producteur", "identityProof": false,
                          "usableAsProducerRef": true }
                        """)
                .when().put("/api/v1/id-document-types/" + typeId)
                .then().statusCode(200)
                .body("data.usableAsProducerRef", equalTo(true));

        // Le numéro est désormais réservé.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Sanogo", "gender": "MALE", "status": "ACTIVE",
                          "identityDocuments": [
                            { "type": "Carte producteur", "number": "carte 42" }
                          ] }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(409)
                .body("statusMessage", containsString("Koffi"));
    }
}
