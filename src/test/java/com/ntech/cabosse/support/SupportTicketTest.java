package com.ntech.cabosse.support;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'assistance intégrée au produit.
 *
 * <p>Les écrans du back-office existaient déjà, servis par un tableau en
 * mémoire : les tickets disparaissaient au redémarrage et la structure
 * n'avait aucun moyen d'en ouvrir un. Ces tests tiennent ce qui, dans un
 * canal d'assistance, se paie cher quand ça lâche : le cloisonnement
 * entre structures, la confidentialité des notes internes, et un cycle de
 * vie qu'un appel direct à l'API ne peut pas contourner.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupportTicketTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity operator;
    private UserEntity otherOperator;
    private UserEntity staff;

    @BeforeEach
    void setUp() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-tck-" + TestFixtures.randomSlugSuffix(), "Structure Assistance");
        TenantEntity neighbour = fixtures.createActiveTenant(
                "coop-tck2-" + TestFixtures.randomSlugSuffix(), "Structure Voisine");
        operator = userIn(tenant, "op@" + tenant.slug + ".ci", Roles.USER);
        otherOperator = userIn(neighbour, "op@" + neighbour.slug + ".ci", Roles.USER);
        staff = fixtures.createPlatformAdmin();
    }

    private UserEntity userIn(TenantEntity tenant, String email, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = email;
        u.firstName = "Ama";
        u.lastName = "Koffi";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private String openTicket(UserEntity user, String subject) {
        return givenAs(user).contentType("application/json")
                .body("""
                        { "subject": "%s",
                          "description": "La pesée refuse les lots de plus de 100 kg.",
                          "category": "INCIDENT" }
                        """.formatted(subject))
                .when().post("/api/v1/me/support/tickets")
                .then().statusCode(201)
                .extract().path("data.id");
    }

    private ValidatableResponse staffReply(String ticketId, String body, boolean internal) {
        return givenAs(staff).contentType("application/json")
                .body("""
                        { "body": "%s", "internal": %s }
                        """.formatted(body, internal))
                .when().post("/api/v1/admin/support/tickets/" + ticketId + "/messages")
                .then();
    }

    private ValidatableResponse moveTo(String ticketId, String status) {
        return givenAs(staff).contentType("application/json")
                .body("{ \"status\": \"%s\" }".formatted(status))
                .when().patch("/api/v1/admin/support/tickets/" + ticketId + "/status")
                .then();
    }

    @Test
    void a_cooperative_opens_a_ticket_and_the_editor_sees_it_in_the_queue() {
        String id = openTicket(operator, "Pesée bloquée");

        givenAs(operator).when().get("/api/v1/me/support/tickets/" + id)
                .then().statusCode(200)
                .body("data.ref", org.hamcrest.Matchers.startsWith("TCK-"))
                .body("data.status", org.hamcrest.Matchers.is("OPEN"))
                .body("data.reportedBy", org.hamcrest.Matchers.is("Ama Koffi"));

        givenAs(staff).when().get("/api/v1/admin/support/tickets")
                .then().statusCode(200)
                .body("data.items.find { it.id == '" + id + "' }.subject",
                        org.hamcrest.Matchers.is("Pesée bloquée"));
    }

    @Test
    void an_internal_note_never_reaches_the_cooperative() {
        String id = openTicket(operator, "Question de paramétrage");
        staffReply(id, "Le correctif part en fin de semaine.", false).statusCode(200);
        staffReply(id, "Contourner en attendant, cf. incident 42.", true).statusCode(200);

        // L'éditeur voit les deux messages…
        givenAs(staff).when().get("/api/v1/admin/support/tickets/" + id)
                .then().statusCode(200).body("data.messages.size()", org.hamcrest.Matchers.is(2));

        // …la structure ne voit que la réponse qui lui était destinée. Le
        // tri se fait à la composition de la réponse : masquer la note
        // dans l'écran la laisserait lisible dans le corps de l'API.
        List<String> bodies = givenAs(operator).when()
                .get("/api/v1/me/support/tickets/" + id)
                .then().statusCode(200).extract().path("data.messages.body");
        assertThat(bodies).containsExactly("Le correctif part en fin de semaine.");
    }

    @Test
    void a_ticket_from_another_cooperative_stays_out_of_reach() {
        String id = openTicket(operator, "Incident de collecte");

        // Les tickets vivent dans une collection commune : le cloisonnement
        // des bases ne protège rien ici, la garde doit être explicite.
        givenAs(otherOperator).when().get("/api/v1/me/support/tickets/" + id)
                .then().statusCode(403);
        givenAs(otherOperator).contentType("application/json")
                .body("{ \"body\": \"Bonjour\", \"internal\": false }")
                .when().post("/api/v1/me/support/tickets/" + id + "/messages")
                .then().statusCode(403);
    }

    @Test
    void the_cooperative_only_lists_its_own_tickets() {
        openTicket(operator, "Chez moi");
        openTicket(otherOperator, "Chez le voisin");

        List<String> subjects = givenAs(operator).when().get("/api/v1/me/support/tickets")
                .then().statusCode(200).extract().path("data.items.subject");
        assertThat(subjects).containsExactly("Chez moi");
    }

    @Test
    void the_life_cycle_holds_even_against_a_direct_call() {
        String id = openTicket(operator, "Transition interdite");
        moveTo(id, "RESOLVED").statusCode(200);

        // Une option grisée dans un sélecteur n'empêche personne d'appeler
        // l'API. Un ticket résolu qui redeviendrait « ouvert » fausserait
        // tout décompte de délai de réponse.
        moveTo(id, "OPEN").statusCode(422);
        moveTo(id, "CLOSED").statusCode(200);
    }

    @Test
    void a_reply_moves_the_ticket_along_but_a_note_does_not() {
        String id = openTicket(operator, "Avancement");

        staffReply(id, "Note pour l'équipe.", true).statusCode(200)
                .body("data.status", org.hamcrest.Matchers.is("OPEN"));

        staffReply(id, "Bonjour, nous regardons.", false).statusCode(200)
                .body("data.status", org.hamcrest.Matchers.is("IN_PROGRESS"));
    }

    @Test
    void the_cooperative_answering_gives_the_ball_back_to_the_editor() {
        String id = openTicket(operator, "Retour attendu");
        moveTo(id, "WAITING").statusCode(200);

        givenAs(operator).contentType("application/json")
                .body("{ \"body\": \"Voici la capture demandée.\", \"internal\": true }")
                .when().post("/api/v1/me/support/tickets/" + id + "/messages")
                .then().statusCode(200)
                .body("data.status", org.hamcrest.Matchers.is("IN_PROGRESS"))
                // La notion de note interne n'existe pas côté structure :
                // le drapeau est ignoré, sans quoi elle croirait avoir
                // répondu à un éditeur qui ne verrait rien.
                .body("data.messages.size()", org.hamcrest.Matchers.is(1));
    }

    @Test
    void requalifying_the_priority_moves_the_commitment_with_it() {
        String id = openTicket(operator, "Bloquant");
        String before = givenAs(staff).when().get("/api/v1/admin/support/tickets/" + id)
                .then().statusCode(200).extract().path("data.slaDeadline");

        givenAs(staff).contentType("application/json").body("{ \"priority\": \"P1\" }")
                .when().patch("/api/v1/admin/support/tickets/" + id + "/priority")
                .then().statusCode(200);

        String after = givenAs(staff).when().get("/api/v1/admin/support/tickets/" + id)
                .then().statusCode(200).extract().path("data.slaDeadline");

        // L'échéance se déduit de la priorité : figée à l'ouverture, elle
        // laisserait un ticket requalifié bloquant avec le délai d'une
        // gêne courante.
        assertThat(after).isNotEqualTo(before);
        assertThat(Instant.parse(after)).isBefore(Instant.parse(before));
    }

    @Test
    void each_ticket_carries_its_own_reference() {
        String first = givenAs(operator).when()
                .get("/api/v1/me/support/tickets/" + openTicket(operator, "Un"))
                .then().extract().path("data.ref");
        String second = givenAs(operator).when()
                .get("/api/v1/me/support/tickets/" + openTicket(operator, "Deux"))
                .then().extract().path("data.ref");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void assigning_and_returning_to_the_queue_both_work() {
        String id = openTicket(operator, "Affectation");

        givenAs(staff).contentType("application/json").body("{ \"assignee\": \"Koffi N.\" }")
                .when().patch("/api/v1/admin/support/tickets/" + id + "/assignee")
                .then().statusCode(200).body("data.assignedTo", org.hamcrest.Matchers.is("Koffi N."));

        givenAs(staff).contentType("application/json").body("{ \"assignee\": null }")
                .when().patch("/api/v1/admin/support/tickets/" + id + "/assignee")
                .then().statusCode(200).body("data.assignedTo", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void a_cooperative_cannot_reach_the_editor_queue() {
        openTicket(operator, "Cloisonnement");

        givenAs(operator).when().get("/api/v1/admin/support/tickets")
                .then().statusCode(403);
    }
}
