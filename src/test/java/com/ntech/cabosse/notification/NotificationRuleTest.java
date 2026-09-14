package com.ntech.cabosse.notification;

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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Le moteur de réglage des notifications (CE-205).
 *
 * <p>L'administrateur décide quels événements déclenchent, vers quels
 * profils, par quels canaux (au moins un) et avec quelles copies. Un
 * événement sans règle garde son comportement d'origine ; la règle est
 * l'exception, pas une recopie du défaut.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class NotificationRuleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Règle";
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

    private UserEntity userWithPhone(String prefix, String phone) {
        UserEntity u = user(prefix, Roles.USER);
        u.phone = phone;
        users.update(u);
        return u;
    }

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-regle-" + TestFixtures.randomSlugSuffix(), "Coopérative Règles");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    private String createRole(UserEntity admin, String name, String... permissions) {
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(name, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
    }

    private void assign(UserEntity admin, UserEntity target, String roleId) {
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + target.id).then().statusCode(204);
    }

    private String requestAdvance(UserEntity who, String delegateId, int amount) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.ref");
    }

    private int unreadOf(UserEntity who) {
        return ((Number) givenAs(who).when().get("/api/v1/notifications/inbox/unread-count")
                .then().statusCode(200).extract().path("data.unread")).intValue();
    }

    @Test
    void the_rule_decides_who_hears_what_and_through_which_channel() {
        UserEntity admin = admin();
        UserEntity approver = user("approbateur", Roles.USER);
        UserEntity governance = user("gouvernance", Roles.USER);
        String approverRole = createRole(admin, "Approbation",
                "COLLECTION_READ", "COLLECTION_ADVANCE_APPROVE");
        String governanceRole = createRole(admin, "Gouvernance", "COLLECTION_READ");
        assign(admin, approver, approverRole);
        assign(admin, governance, governanceRole);
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Délégué Règles\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        // ─── Le catalogue et ses défauts ───
        givenAs(admin).when().get("/api/v1/notifications/rules")
                .then().statusCode(200)
                .body("data", hasSize(10))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.enabled",
                        equalTo(true))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.channels",
                        hasItem("EMAIL"))
                .body("data.find { it.eventCode == 'collector-advance.pending-approval' }.channels",
                        hasItem("IN_APP"))
                .body("data.find { it.eventCode == 'advance-refund.reported' }.audienceConfigurable",
                        equalTo(false));

        // ─── Un événement actif exige au moins un canal ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(422);

        // ─── Application seule : le courriel se tait ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"IN_APP\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref1 = requestAdvance(admin, delegateId, 100_000);
        assertThat(unreadOf(approver)).isEqualTo(1);
        List<Map<String, Object>> emails = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails.stream().anyMatch(d -> ref1.equals(d.get("subjectRef")))).isFalse();

        // ─── Les profils remplacent l'audience par défaut ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"IN_APP\"], \"recipientRoleIds\": [\"%s\"] }"
                        .formatted(governanceRole))
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        requestAdvance(admin, delegateId, 150_000);
        assertThat(unreadOf(governance)).isEqualTo(1);
        // L'approbateur n'est plus dans l'audience : son compteur ne bouge pas.
        assertThat(unreadOf(approver)).isEqualTo(1);

        // ─── Les copies partent par courriel ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\", \"IN_APP\"], "
                        + "\"recipientRoleIds\": [\"%s\"], \"ccEmails\": [\"controle@exemple.ci\"] }"
                        .formatted(governanceRole))
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref3 = requestAdvance(admin, delegateId, 200_000);
        List<Map<String, Object>> emails3 = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails3.stream().anyMatch(d ->
                ref3.equals(d.get("subjectRef")) && "controle@exemple.ci".equals(d.get("target"))))
                .isTrue();

        // ─── Éteint, l'événement se tait partout ───
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": false, \"channels\": [\"EMAIL\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);
        String ref4 = requestAdvance(admin, delegateId, 250_000);
        // Le scénario des copies a déjà porté la gouvernance à deux.
        assertThat(unreadOf(governance)).isEqualTo(2);
        List<Map<String, Object>> emails4 = givenAs(admin)
                .when().get("/api/v1/notifications/journal?channel=EMAIL&limit=100")
                .then().statusCode(200).extract().path("data");
        assertThat(emails4.stream().anyMatch(d -> ref4.equals(d.get("subjectRef")))).isFalse();

        // Une adresse en copie invalide est refusée.
        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\"], \"ccEmails\": [\"pas-une-adresse\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(422);
    }

    /**
     * Le SMS part en plus du courriel, quand la structure l'a réglé
     * ainsi et que le destinataire a un téléphone.
     *
     * <p>Demandé le 14/09/2026 : prévenir par courriel suppose une boîte
     * relevée, ce qui ne va pas de soi pour un président de conseil en
     * déplacement. Le canal existait dans le socle sans qu'on ait
     * vérifié qu'un événement d'approbation l'empruntait vraiment.</p>
     *
     * <p>Deux conditions, et le message ne part pas si l'une manque :
     * le canal retenu sur la règle, et un numéro au profil. Un compte
     * sans téléphone continue de recevoir le courriel, sans qu'aucune
     * ligne ne reste coincée dans la file.</p>
     */
    @Test
    void an_approval_also_goes_out_by_text_when_the_profile_carries_a_phone() {
        UserEntity admin = admin();
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"BABA OUEDRAOGO\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");

        String approverRole = createRole(admin, "Approbateurs", "COLLECTION_ADVANCE_APPROVE");
        UserEntity director = userWithPhone("directeur", "+2250565710326");
        UserEntity chair = userWithPhone("president", "+2250707008700");
        // Un troisième approbateur sans numéro : il ne doit rien coincer.
        UserEntity auditor = user("commissaire", Roles.USER);
        for (UserEntity who : List.of(director, chair, auditor)) {
            assign(admin, who, approverRole);
        }

        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\", \"SMS\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);

        requestAdvance(admin, delegateId, 2_000_000);

        // Le tenant vient de naître : la file ne porte que cette alerte.
        List<String> smsTargets = givenAs(admin).queryParam("channel", "SMS")
                .when().get("/api/v1/notifications/journal").then().statusCode(200)
                .extract().path("data.target");
        assertThat(smsTargets)
                .containsExactlyInAnyOrder("+2250565710326", "+2250707008700");

        // Le courriel part toujours, y compris à qui n'a pas de numéro :
        // ajouter un canal n'en retire aucun, et personne n'est oublié
        // parce qu'il n'a pas donné de téléphone.
        List<String> mailTargets = givenAs(admin).queryParam("channel", "EMAIL")
                .when().get("/api/v1/notifications/journal").then().statusCode(200)
                .extract().path("data.target");
        assertThat(mailTargets)
                .containsExactlyInAnyOrder(director.email, chair.email, auditor.email);
    }

    /**
     * L'administrateur de la structure ne reçoit aucune alerte métier.
     *
     * <p>Il porte tous les droits par construction : il tombait donc
     * dans toutes les audiences, et recevait chaque avance, chaque
     * règlement, chaque demande, y compris par SMS et aux frais de la
     * structure. Son rôle est le paramétrage et les comptes, pas
     * l'exploitation (14/09/2026).</p>
     */
    @Test
    void the_tenant_administrator_hears_nothing_of_the_daily_business() {
        UserEntity admin = admin();
        String delegateId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"BABA OUEDRAOGO\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");

        String approverRole = createRole(admin, "Approbateurs", "COLLECTION_ADVANCE_APPROVE");
        UserEntity director = userWithPhone("directeur", "+2250565710326");
        assign(admin, director, approverRole);
        // Celui qui dépose n'approuve pas : il faut donc un troisième
        // compte pour que l'audience ne soit pas vidée par l'exclusion.
        String requesterRole = createRole(admin, "Gestionnaires", "COLLECTION_ADVANCE_REQUEST");
        UserEntity manager = user("gestionnaire", Roles.USER);
        assign(admin, manager, requesterRole);

        givenAs(admin).contentType("application/json")
                .body("{ \"enabled\": true, \"channels\": [\"EMAIL\", \"SMS\", \"IN_APP\"] }")
                .when().put("/api/v1/notifications/rules/collector-advance.pending-approval")
                .then().statusCode(200);

        // Déposée par le gestionnaire : l'administrateur n'est ni
        // déposant ni exclu de gouvernance, rien ne le retirait de
        // l'audience avant ce jour.
        requestAdvance(manager, delegateId, 2_000_000);

        List<String> mails = givenAs(admin).queryParam("channel", "EMAIL")
                .when().get("/api/v1/notifications/journal").then().statusCode(200)
                .extract().path("data.target");
        assertThat(mails).doesNotContain(admin.email);

        // Aucun SMS non plus : l'administrateur n'a pas de numéro ici,
        // mais la règle vaut même s'il en avait un.
        List<String> texts = givenAs(admin).queryParam("channel", "SMS")
                .when().get("/api/v1/notifications/journal").then().statusCode(200)
                .extract().path("data.target");
        assertThat(texts).containsExactly("+2250565710326");

        // Et sa boîte de réception reste vide, quand le directeur a bien
        // été prévenu : l'alerte est partie, elle l'a seulement ignoré.
        assertThat(unreadOf(admin)).isZero();
        assertThat(unreadOf(director)).isEqualTo(1);
    }
}
