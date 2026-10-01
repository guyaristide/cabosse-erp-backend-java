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

import static org.hamcrest.Matchers.equalTo;

/**
 * Corriger une demande d'avance que personne n'a encore approuvée.
 *
 * <p>Une erreur de saisie n'avait aucune issue : il fallait rejeter la
 * demande, qui restait au journal sous un refus qu'elle n'avait pas
 * mérité, puis en ressaisir une (demandé le 01/10/2026). Tant que rien
 * n'est approuvé, rien n'est sorti ni écrit : la demande se corrige comme
 * un brouillon.</p>
 *
 * <p>Ce que ces tests tiennent : que la correction redérive tout ce que la
 * création dérivait, et qu'elle s'arrête net dès qu'une décision a été
 * prise, de l'argent étant alors engagé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AdvanceCorrectionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-corr-" + TestFixtures.randomSlugSuffix(), "Coopérative Correction");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Correction";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 50_000_000);
        return u;
    }

    private String createSection(UserEntity admin, String code, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true,\"sectionId\":\""
                        + sectionId + "\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String request(UserEntity admin, String delegateId, String siteId, int amount) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201).extract().path("data.id");
    }

    private io.restassured.specification.RequestSpecification correction(
            UserEntity admin, String delegateId, int amount) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmount": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount));
    }

    @Test
    void une_demande_en_attente_se_corrige() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "MEAGUI", "Section Méagui");
        String delegateId = createDelegate(admin, "KONE Adama", sectionId);
        String siteId = createSite(admin);
        String id = request(admin, delegateId, siteId, 1_200_000);

        correction(admin, delegateId, 600_000)
                .when().put("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                .body("data.advanceAmount", equalTo(600000))
                // Rien n'a été consommé : le reste disponible suit le
                // montant corrigé, sans quoi la demande garderait le
                // plafond de la saisie fautive.
                .body("data.remaining", equalTo(600000))
                .body("data.status", equalTo("PENDING_APPROVAL"));
    }

    @Test
    void la_correction_garde_la_meme_demande() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "SOUBRE", "Section Soubré");
        String delegateId = createDelegate(admin, "TRAORE Salif", sectionId);
        String siteId = createSite(admin);
        String id = request(admin, delegateId, siteId, 800_000);
        String ref = givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200).extract().path("data.ref");

        correction(admin, delegateId, 900_000)
                .when().put("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                // C'est la même demande, corrigée, pas une autre : sa
                // référence ne bouge pas, sinon le délégué recevrait deux
                // numéros pour un seul engagement.
                .body("data.ref", equalTo(ref))
                .body("data.id", equalTo(id));
    }

    @Test
    void changer_de_delegue_rederive_sa_section() {
        UserEntity admin = tenantAdmin();
        String meagui = createSection(admin, "MEAGUI", "Section Méagui");
        String soubre = createSection(admin, "SOUBRE", "Section Soubré");
        String premier = createDelegate(admin, "KONE Adama", meagui);
        String second = createDelegate(admin, "YAO Kouamé", soubre);
        String siteId = createSite(admin);
        String id = request(admin, premier, siteId, 500_000);

        // Se tromper de délégué est l'erreur la plus coûteuse : tout ce
        // qui en dépend doit suivre, pas seulement son nom.
        correction(admin, second, 500_000)
                .when().put("/api/v1/collector-advances/" + id)
                .then().statusCode(200)
                .body("data.delegateName", equalTo("YAO Kouamé"))
                .body("data.sectionName", equalTo("Section Soubré"));
    }

    @Test
    void une_demande_approuvee_ne_se_corrige_plus() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "MEAGUI", "Section Méagui");
        String delegateId = createDelegate(admin, "KONE Adama", sectionId);
        String siteId = createSite(admin);
        String id = request(admin, delegateId, siteId, 400_000);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);

        // Une décision a été prise sur un montant : le changer après coup
        // ferait approuver autre chose que ce qui a été examiné.
        correction(admin, delegateId, 900_000)
                .when().put("/api/v1/collector-advances/" + id)
                .then().statusCode(422);

        givenAs(admin).when().get("/api/v1/collector-advances/" + id)
                .then().statusCode(200).body("data.advanceAmount", equalTo(400000));
    }

    @Test
    void une_demande_decaissee_ne_se_corrige_plus() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "MEAGUI", "Section Méagui");
        String delegateId = createDelegate(admin, "KONE Adama", sectionId);
        String siteId = createSite(admin);
        String id = request(admin, delegateId, siteId, 300_000);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);

        // L'argent est sorti et l'écriture est passée : la corriger
        // laisserait le journal et la demande dire deux choses
        // différentes.
        correction(admin, delegateId, 100_000)
                .when().put("/api/v1/collector-advances/" + id)
                .then().statusCode(422);
    }
}
