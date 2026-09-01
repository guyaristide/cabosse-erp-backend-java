package com.ntech.cabosse.collector;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'alerte qui prévient qu'une avance attend une décision.
 *
 * <p>Constaté par l'expert filière le 31/08/2026 : « pour le volet
 * validation, un message est parti ? ». Aucun ne partait. La file d'envoi
 * existait depuis le 21/08, mais aucun événement métier ne l'alimentait :
 * le socle était posé, les alertes ne l'étaient pas.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceApprovalAlertTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-ntf-" + TestFixtures.randomSlugSuffix(), "Coopérative Alerte");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return userIn(tenant, "admin");
    }

    private UserEntity userIn(TenantEntity t, String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + t.slug + ".ci";
        u.firstName = "Agent";
        u.lastName = "Alerte";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = t.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private String openCampaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKgFcfa": 900 }
                        """.formatted(TestFixtures.randomSlugSuffix(),
                        LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5)))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    private String delegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String requestAdvance(UserEntity who, String delegateId, String campaignId) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": 1500000, "paymentMethod": "CHEQUE",
                          "campaignId": "%s" }
                        """.formatted(delegateId, LocalDate.now(), campaignId))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.ref");
    }

    /**
     * Destinataires des envois enfilés pour cette avance.
     *
     * <p>Le journal est propre au tenant : le lire depuis la structure
     * elle-même vérifie du même coup qu'aucune autre n'y figure.</p>
     */
    private List<String> queuedFor(UserEntity who, String ref) {
        return givenAs(who).when()
                .get("/api/v1/notifications/journal?limit=100")
                .then().statusCode(200)
                .extract().path("data.findAll { it.subjectRef == '" + ref + "' }.target");
    }

    @Test
    void a_pending_advance_alerts_whoever_can_approve_it() {
        UserEntity requester = admin();
        UserEntity approver = userIn(tenant, "approbateur");
        String campaign = openCampaign(requester);
        String ref = requestAdvance(requester, delegate(requester, "KONE Adama"), campaign);

        // Le délégué reste sans fonds tant que personne n'a décidé : c'est
        // ce qui rend l'alerte utile, et non le simple fait qu'une ligne
        // ait été écrite.
        assertThat(queuedFor(requester, ref)).contains(approver.email);
    }

    @Test
    void the_person_who_filed_it_is_not_asked_to_decide() {
        UserEntity requester = admin();
        userIn(tenant, "approbateur");
        String campaign = openCampaign(requester);
        String ref = requestAdvance(requester, delegate(requester, "YAO Brou"), campaign);

        // Il n'approuvera pas sa propre demande : la règle des deux paires
        // d'yeux le lui refuse, et l'inviter à décider n'aurait aucun sens.
        assertThat(queuedFor(requester, ref)).doesNotContain(requester.email);
    }

    @Test
    void one_organization_is_never_alerted_for_another() {
        UserEntity first = admin();
        UserEntity outsider = userIn(tenant, "voisin");

        UserEntity second = admin();
        String ref = requestAdvance(second, delegate(second, "TRAORE Solange"),
                openCampaign(second));

        // Le voisin détient pourtant le même droit d'approbation, mais
        // dans une autre structure.
        assertThat(queuedFor(second, ref)).doesNotContain(outsider.email);
        assertThat(first.email).isNotEqualTo(second.email);
    }
}
