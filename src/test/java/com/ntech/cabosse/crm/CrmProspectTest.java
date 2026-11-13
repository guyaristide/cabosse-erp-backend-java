package com.ntech.cabosse.crm;

import com.ntech.cabosse.auth.service.PasswordHasher;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Le petit CRM du back-office (SAAS-31, demandé le 13/11/2026) : un
 * prospect s'ouvre, avance d'étape en étape, consigne ses échanges, se
 * relie à la structure créée ; une structure existante tient aussi son
 * fil et sa prochaine action.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CrmProspectTest extends AbstractIntegrationTest {

    private static final String PROSPECTS = "/api/v1/admin/crm/prospects";

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin(TenantEntity tenant) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Structure";
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

    @Test
    void un_prospect_avance_consigne_ses_echanges_puis_devient_une_structure() {
        UserEntity admin = fixtures.createPlatformAdmin(
                "crm-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com", "Aya", "Koné");
        String name = "Coopérative " + TestFixtures.randomSlugSuffix();

        // Ouvert au premier contact, avec la personne rencontrée.
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "name": "%s", "organizationModel": "COOPERATIVE", "industry": "Cacao",
                          "country": "Côte d'Ivoire", "city": "Soubré", "source": "Recommandation",
                          "contacts": [{ "name": "Kouadio Brou", "role": "Président", "phone": "0707070707" }],
                          "nextActionAt": "%s", "nextActionLabel": "Rappeler pour fixer la démo" }
                        """.formatted(name, LocalDate.now().minusDays(1)))
                .when().post(PROSPECTS).then().statusCode(201)
                .body("data.stage", equalTo("CONTACTED"))
                .body("data.ownerEmail", equalTo(admin.email))
                .body("data.contacts", hasSize(1))
                .body("data.tenantId", nullValue())
                .extract().path("data.id");

        // Le pipeline le compte, et compte son action en retard.
        givenAs(admin).when().get(PROSPECTS + "/summary").then().statusCode(200)
                .body("data.byStage.CONTACTED", greaterThanOrEqualTo(1))
                .body("data.dueCount", greaterThanOrEqualTo(1));
        givenAs(admin).when().get(PROSPECTS + "?q=soubr").then().statusCode(200)
                .body("data.items.findAll { it.id == '" + id + "' }", hasSize(1));

        // Un appel passé : consigné, et la prochaine action posée dans le même geste.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "CALL", "date": "%s", "summary": "Démo fixée pour jeudi.",
                          "nextActionAt": "%s", "nextActionLabel": "Démo à la coopérative" }
                        """.formatted(LocalDate.now(), LocalDate.now().plusDays(3)))
                .when().post(PROSPECTS + "/" + id + "/interactions").then().statusCode(201)
                .body("data.authorName", equalTo("Aya Koné"));
        givenAs(admin).when().get(PROSPECTS + "/" + id).then().statusCode(200)
                .body("data.nextActionLabel", equalTo("Démo à la coopérative"));
        givenAs(admin).when().get(PROSPECTS + "/" + id + "/interactions").then().statusCode(200)
                .body("data", hasSize(1));

        // Il avance ; perdu garde son motif, revenu l'efface.
        givenAs(admin).contentType("application/json").body("{ \"stage\": \"DEMO\" }")
                .when().patch(PROSPECTS + "/" + id + "/stage").then().statusCode(200)
                .body("data.stage", equalTo("DEMO"));
        givenAs(admin).contentType("application/json")
                .body("{ \"stage\": \"LOST\", \"lostReason\": \"Budget reporté\" }")
                .when().patch(PROSPECTS + "/" + id + "/stage").then().statusCode(200)
                .body("data.lostReason", equalTo("Budget reporté"));
        givenAs(admin).contentType("application/json").body("{ \"stage\": \"NEGOTIATION\" }")
                .when().patch(PROSPECTS + "/" + id + "/stage").then().statusCode(200)
                .body("data.lostReason", nullValue());

        // Gagné : la structure créée se relie, et le fil la suit.
        TenantEntity tenant = fixtures.createActiveTenant(
                "crm-" + TestFixtures.randomSlugSuffix(), name);
        givenAs(admin).contentType("application/json")
                .body("{ \"tenantId\": \"" + tenant.id + "\" }")
                .when().post(PROSPECTS + "/" + id + "/tenant").then().statusCode(200)
                .body("data.stage", equalTo("WON"))
                .body("data.tenantId", equalTo(tenant.id.toString()))
                .body("data.tenantName", equalTo(name));
        givenAs(admin).when().get("/api/v1/admin/crm/tenants/" + tenant.id + "/interactions")
                .then().statusCode(200).body("data", hasSize(1));

        // Devenu structure, le prospect ne s'efface plus.
        givenAs(admin).when().delete(PROSPECTS + "/" + id).then().statusCode(409);
    }

    @Test
    void une_structure_tient_son_fil_et_sa_prochaine_action() {
        UserEntity admin = fixtures.createPlatformAdmin(
                "crm-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com", "Aya", "Koné");
        TenantEntity tenant = fixtures.createActiveTenant(
                "crm-" + TestFixtures.randomSlugSuffix(), "Structure suivie");
        String base = "/api/v1/admin/crm/tenants/" + tenant.id;

        givenAs(admin).when().get(base + "/follow-up").then().statusCode(200)
                .body("data.nextActionAt", nullValue());

        givenAs(admin).contentType("application/json")
                .body("""
                        { "kind": "VISIT", "date": "%s", "summary": "Visite de la coopérative, renouvellement évoqué.",
                          "nextActionAt": "%s", "nextActionLabel": "Envoyer le devis de renouvellement" }
                        """.formatted(LocalDate.now(), LocalDate.now().plusDays(7)))
                .when().post(base + "/interactions").then().statusCode(201)
                .body("data.tenantId", equalTo(tenant.id.toString()));
        givenAs(admin).when().get(base + "/follow-up").then().statusCode(200)
                .body("data.nextActionLabel", equalTo("Envoyer le devis de renouvellement"))
                .body("data.ownerEmail", equalTo(admin.email))
                .body("data.updatedAt", notNullValue());

        // Fait : la prochaine action s'efface.
        givenAs(admin).contentType("application/json").body("{}")
                .when().put(base + "/follow-up").then().statusCode(200)
                .body("data.nextActionAt", nullValue());
        givenAs(admin).when().get(base + "/interactions").then().statusCode(200).body("data", hasSize(1));
    }

    @Test
    void un_prospect_sans_structure_s_efface_avec_son_fil_et_le_reste_est_reserve_a_l_editeur() {
        UserEntity admin = fixtures.createPlatformAdmin(
                "crm-" + TestFixtures.randomSlugSuffix() + "@neiba-technologies.com", "Aya", "Koné");
        String id = givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"Société Hévéa " + TestFixtures.randomSlugSuffix() + "\" }")
                .when().post(PROSPECTS).then().statusCode(201).extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .body("{ \"kind\": \"EMAIL\", \"date\": \"%s\", \"summary\": \"Plaquette envoyée.\" }".formatted(LocalDate.now()))
                .when().post(PROSPECTS + "/" + id + "/interactions").then().statusCode(201);

        givenAs(admin).when().delete(PROSPECTS + "/" + id).then().statusCode(204);
        givenAs(admin).when().get(PROSPECTS + "/" + id).then().statusCode(404);
        givenAs(admin).when().get(PROSPECTS + "/" + id + "/interactions").then().statusCode(404);

        // Un compte de structure n'y entre pas.
        TenantEntity tenant = fixtures.createActiveTenant("crm-" + TestFixtures.randomSlugSuffix(), "Autre");
        UserEntity tenantAdmin = tenantAdmin(tenant);
        givenAs(tenantAdmin).when().get(PROSPECTS).then().statusCode(403);
    }
}
