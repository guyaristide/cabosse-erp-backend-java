package com.ntech.cabosse.supplier;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Le délégué qui n'est pas sociétaire (relevé le 28/09/2026).
 *
 * <p>Un délégué collecte les fèves d'une localité, reçoit des avances et
 * livre au magasin. Rien n'oblige la coopérative à en faire un membre :
 * ce peut être un prestataire extérieur, et c'est fréquent.</p>
 *
 * <p>Le modèle le portait déjà correctement, la qualité de délégué vivant
 * sur la fiche fournisseur. Une chose y échappait : la rémunération
 * négociée campagne par campagne ne se posait que depuis la fiche du
 * producteur. Un prestataire extérieur n'en a pas, il ne pouvait donc
 * recevoir aucun taux convenu, et le calcul retombait <strong>en
 * silence</strong> sur le taux commun. Rien ne signalait la perte : ni
 * refus, ni message, seulement un montant plus faible que l'entente.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class NonMemberDelegateTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-nmd-" + TestFixtures.randomSlugSuffix(), "Coopérative Prestataires");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Prestataires";
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

    /** Un délégué qui n'existe que comme fournisseur : aucune adhésion. */
    @SuppressWarnings("SameParameterValue")
    private String delegate(UserEntity who, String name) {
        return givenAs(who).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"collector\":true}")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");
    }

    private String campaign(UserEntity who, String label, String from, String to) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "%s", "startDate": "%s", "endDate": "%s",
                          "basePricePerKg": 1000 }
                        """.formatted(label, from, to))
                .when().post("/api/v1/campaigns").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void un_delegue_sans_adhesion_recoit_un_taux_negocie() {
        UserEntity a = admin();
        String id = delegate(a, "Prestataire Extérieur");
        int year = LocalDate.now().getYear();
        String c = campaign(a, "Campagne " + year, year + "-09-01", (year + 1) + "-02-28");

        // Le geste qui était impossible : ce délégué n'a pas de fiche
        // producteur, et c'était la seule porte vers ces taux.
        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 42 } ] }
                        """.formatted(c))
                .when().put("/api/v1/suppliers/" + id + "/collector-margins")
                .then().statusCode(200)
                .body("data.collectorMarginByCampaign", hasSize(1))
                .body("data.collectorMarginByCampaign[0].rate", equalTo(42));

        // Relu par la liste : le référentiel fournisseurs n'expose pas
        // de route de détail, l'écran travaille sur la liste.
        givenAs(a).queryParam("q", "Prestataire Extérieur").when().get("/api/v1/suppliers")
                .then().statusCode(200)
                .body("data.items[0].collectorMarginByCampaign", hasSize(1));
    }

    @Test
    void aucune_fiche_membre_n_est_fabriquee_au_passage() {
        UserEntity a = admin();
        String id = delegate(a, "Toujours Prestataire");

        // Un délégué n'est pas un sociétaire : lui ouvrir un dossier
        // d'adhésion pour le seul besoin d'un taux fausserait l'effectif
        // et les parts sociales.
        givenAs(a).when().get("/api/v1/members?perPage=100")
                .then().statusCode(200)
                .body("data.items.name", not(org.hamcrest.Matchers.hasItem("Toujours Prestataire")));
        givenAs(a).queryParam("q", "Toujours Prestataire").when().get("/api/v1/suppliers")
                .then().statusCode(200).body("data.items[0].collector", equalTo(true));
    }

    @Test
    void un_fournisseur_ordinaire_ne_recoit_pas_de_taux_de_delegue() {
        UserEntity a = admin();
        String id = givenAs(a).contentType("application/json")
                .body("{\"name\":\"Quincaillerie du Marché\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
        int year = LocalDate.now().getYear();
        String c = campaign(a, "Campagne Q" + year, year + "-09-01", (year + 1) + "-02-28");

        // La qualité se juge sur le fournisseur, où elle se porte, et non
        // sur la copie que garde une fiche producteur : les deux peuvent
        // diverger, et c'est la copie qui a tort.
        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 30 } ] }
                        """.formatted(c))
                .when().put("/api/v1/suppliers/" + id + "/collector-margins")
                .then().statusCode(422);
    }

    @Test
    void le_taux_pose_au_fournisseur_se_lit_sur_la_fiche_du_producteur() {
        UserEntity a = admin();
        int year = LocalDate.now().getYear();
        String c = campaign(a, "Campagne M" + year, year + "-09-01", (year + 1) + "-02-28");

        // Un délégué qui est aussi sociétaire a deux fiches. Le taux posé
        // par l'une doit se lire sur l'autre, sinon la rémunération
        // convenue a deux réponses selon l'écran ouvert.
        String memberId = givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "KOUASSI", "firstName": "Yao", "gender": "MALE",
                          "status": "ACTIVE", "collector": true, "joinedAt": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
        String supplierId = givenAs(a).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200).extract().path("data.supplierId");

        givenAs(a).contentType("application/json")
                .body("""
                        { "margins": [ { "campaignId": "%s", "rate": 55 } ] }
                        """.formatted(c))
                .when().put("/api/v1/suppliers/" + supplierId + "/collector-margins")
                .then().statusCode(200);

        givenAs(a).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.collectorMarginByCampaign", hasSize(1))
                .body("data.collectorMarginByCampaign[0].rate", equalTo(55));
    }
}
