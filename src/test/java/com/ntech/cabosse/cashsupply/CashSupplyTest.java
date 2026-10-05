package com.ntech.cabosse.cashsupply;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Alimenter la caisse depuis la banque, en trois mains.
 *
 * <p>La direction demande, la gouvernance accorde, la caisse exécute
 * (demandé le 03/10/2026). Le geste existait sous la forme d'un
 * transport de fonds que la caissière saisissait seule : rien ne disait
 * qui l'avait décidé.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CashSupplyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private TenantEntity tenant;

    /** Une structure migrée : les comptes s'appuient sur le plan comptable. */
    private UserEntity director() {
        tenant = fixtures.createActiveTenant(
                "coop-appro-" + TestFixtures.randomSlugSuffix(), "Coopérative Approvisionnement");
        migrations.runMigrationsFor(tenant.databaseName);
        return userIn("directeur");
    }

    /** Le président : qui demande ne tranche pas. */
    private UserEntity chairman() {
        return userIn("pca");
    }

    private UserEntity userIn(String prefix) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "Coop";
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

    private String account(UserEntity a, String kind, String label, String syscohada) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "kind": "%s", "label": "%s", "syscohadaAccount": "%s", "bankName": "%s" }
                        """.formatted(kind, label, syscohada, label))
                .when().post("/api/v1/accounting/bank-accounts")
                .then().statusCode(201).extract().path("data.id");
    }

    private String request(UserEntity a, String cashId, int amount) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "cashAccountId": "%s", "amount": %d,
                          "reason": "La caisse est à sec avant la collecte de lundi" }
                        """.formatted(cashId, amount))
                .when().post("/api/v1/cash-supplies")
                .then().statusCode(201).extract().path("data.id");
    }

    @Test
    void la_demande_accordee_engendre_le_transport_de_fonds() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String bank = account(directeur, "BANQUE", "Banque Abidjan", "521000");
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");

        String id = request(directeur, cash, 5_000_000);

        givenAs(pca).contentType("application/json").body("{}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(200)
                .body("data.status", equalTo("APPROVED"))
                .body("data.effectiveAmount", equalTo(5000000));

        givenAs(directeur).contentType("application/json")
                .body("""
                        { "bankAccountId": "%s", "chequeNumber": "0001234",
                          "carrierName": "Chauffeur Koffi" }
                        """.formatted(bank))
                .when().post("/api/v1/cash-supplies/" + id + "/fulfill")
                .then().statusCode(200)
                .body("data.status", equalTo("FULFILLED"))
                .body("data.chequeNumber", equalTo("0001234"))
                .body("data.transferRef", notNullValue());

        // L'argent est en route : c'est le transport qui porte la suite,
        // réception et écart compris.
        givenAs(directeur).when().get("/api/v1/treasury/transfers")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].status", equalTo("IN_TRANSIT"))
                .body("data.items[0].amountSent", equalTo(5000000))
                .body("data.items[0].carrierName", equalTo("Chauffeur Koffi"));
    }

    @Test
    void le_montant_accorde_pilote_le_cheque() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String bank = account(directeur, "BANQUE", "Banque Abidjan", "521000");
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");

        String id = request(directeur, cash, 5_000_000);

        // La banque ne suit pas : le conseil accorde moins.
        givenAs(pca).contentType("application/json")
                .body("{\"approvedAmount\": 3000000, \"note\": \"Le reste à la prochaine rentrée\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(200)
                .body("data.effectiveAmount", equalTo(3000000));

        givenAs(directeur).contentType("application/json")
                .body("{\"bankAccountId\": \"" + bank + "\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/fulfill")
                .then().statusCode(200);

        // Lire le montant sollicité ferait préparer un chèque de trop.
        givenAs(directeur).when().get("/api/v1/treasury/transfers")
                .then().statusCode(200)
                .body("data.items[0].amountSent", equalTo(3000000));
    }

    @Test
    void accorder_plus_que_demande_est_refuse() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 1_000_000);

        givenAs(pca).contentType("application/json")
                .body("{\"approvedAmount\": 1500000}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(422);

        // Accorder zéro n'est pas accorder : c'est un refus, et un refus
        // demande une raison.
        givenAs(pca).contentType("application/json")
                .body("{\"approvedAmount\": 0}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void on_ne_tranche_pas_sa_propre_demande() {
        UserEntity directeur = director();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 1_000_000);

        givenAs(directeur).contentType("application/json").body("{}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(422);

        givenAs(directeur).contentType("application/json")
                .body("{\"reason\":\"Finalement non\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/reject")
                .then().statusCode(422);
    }

    @Test
    void rien_ne_part_sans_decision() {
        UserEntity directeur = director();
        String bank = account(directeur, "BANQUE", "Banque Abidjan", "521000");
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 1_000_000);

        givenAs(directeur).contentType("application/json")
                .body("{\"bankAccountId\": \"" + bank + "\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/fulfill")
                .then().statusCode(422);

        givenAs(directeur).when().get("/api/v1/treasury/transfers")
                .then().statusCode(200).body("data.items", hasSize(0));
    }

    @Test
    void la_caisse_ne_se_remplit_que_depuis_une_banque() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        givenAs(directeur).contentType("application/json")
                .body("""
                        { "number": "571100", "label": "Caisse de section",
                          "family": "TRESORERIE" }
                        """)
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        String petty = account(directeur, "CAISSE", "Caisse de section", "571100");
        String id = request(directeur, cash, 200_000);

        givenAs(pca).contentType("application/json").body("{}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve").then().statusCode(200);

        // Des espèces sans origine bancaire ne se rapprochent d'aucun
        // relevé : la garde du transport vaut aussi par ce chemin.
        givenAs(directeur).contentType("application/json")
                .body("{\"bankAccountId\": \"" + petty + "\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/fulfill")
                .then().statusCode(422);
    }

    @Test
    void un_refus_porte_sa_raison_et_ferme_la_demande() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 900_000);

        givenAs(pca).contentType("application/json")
                .body("{\"reason\":\"La banque ne suit pas cette semaine\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/reject")
                .then().statusCode(200)
                .body("data.status", equalTo("REJECTED"))
                .body("data.rejectionReason", equalTo("La banque ne suit pas cette semaine"))
                .body("data.approvedAmount", nullValue());

        givenAs(pca).contentType("application/json").body("{}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve")
                .then().statusCode(422);
    }

    @Test
    void la_demande_remonte_dans_la_file_d_approbation() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        request(directeur, cash, 750_000);

        givenAs(pca).queryParam("kind", "CASH_SUPPLY")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].amount", equalTo(750000))
                .body("data.page.items[0].beneficiaryName", equalTo("Caisse centrale"))
                // Le motif tient lieu de commentaire : la demande se
                // tranche sur une raison.
                .body("data.page.items[0].requesterNote",
                        equalTo("La caisse est à sec avant la collecte de lundi"))
                .body("data.page.items[0].actionable", equalTo(true));

        // Qui a déposé la demande ne la voit pas comme décidable : lui
        // offrir le bouton l'enverrait au-devant d'un refus.
        givenAs(directeur).queryParam("kind", "CASH_SUPPLY")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items[0].actionable", equalTo(false));
    }

    @Test
    void une_demande_retiree_quitte_la_file() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 400_000);

        givenAs(directeur).contentType("application/json")
                .body("{\"reason\":\"La caisse a été remplie autrement\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/cancel")
                .then().statusCode(200).body("data.status", equalTo("CANCELLED"));

        givenAs(pca).queryParam("kind", "CASH_SUPPLY")
                .when().get("/api/v1/governance/approvals")
                .then().statusCode(200)
                .body("data.page.items", hasSize(0));
    }

    @Test
    void la_demande_laisse_sa_trace_a_l_audit() {
        UserEntity directeur = director();
        UserEntity pca = chairman();
        String bank = account(directeur, "BANQUE", "Banque Abidjan", "521000");
        String cash = account(directeur, "CAISSE", "Caisse centrale", "571000");
        String id = request(directeur, cash, 600_000);

        givenAs(pca).contentType("application/json").body("{}")
                .when().post("/api/v1/cash-supplies/" + id + "/approve").then().statusCode(200);
        givenAs(directeur).contentType("application/json")
                .body("{\"bankAccountId\": \"" + bank + "\"}")
                .when().post("/api/v1/cash-supplies/" + id + "/fulfill").then().statusCode(200);

        // Trois mains, trois traces : c'est tout l'objet du circuit. Le
        // journal est tenu par la plateforme, qui seule le relit.
        givenAs(fixtures.createPlatformAdmin())
                .queryParam("tenantId", tenant.id.toString()).queryParam("pageSize", 200)
                .when().get("/api/v1/admin/audit")
                .then().statusCode(200)
                .body("data.items.eventType", hasItem("CASH_SUPPLY_REQUESTED"))
                .body("data.items.eventType", hasItem("CASH_SUPPLY_APPROVED"))
                .body("data.items.eventType", hasItem("CASH_SUPPLY_FULFILLED"));
    }
}
