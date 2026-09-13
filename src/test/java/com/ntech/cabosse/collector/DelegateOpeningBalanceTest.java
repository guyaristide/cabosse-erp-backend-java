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
 * Le solde qu'un délégué traîne à l'ouverture d'une campagne.
 *
 * <p>Signalé par l'expert-comptable le 12/09/2026 : sur la première
 * campagne portée par l'outil, des délégués arrivaient débiteurs et leur
 * compte courant démarrait à zéro. Le report se calcule seul d'une
 * campagne à la suivante, mais il n'y a rien avant la première.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateOpeningBalanceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-dob-" + TestFixtures.randomSlugSuffix(), "Coopérative Reprise");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Reprise";
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

    private String campaign(UserEntity who) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "label": "Campagne %s", "kind": "MAIN", "startDate": "%s",
                          "endDate": "%s", "basePricePerKg": 1200 }
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

    @Test
    void a_declared_opening_balance_carries_into_the_delegate_account() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String delegateId = delegate(admin, "FOFANA KARNA");

        // Rien de déclaré : le compte démarre à zéro, ce qui est faux
        // pour un délégué qui doit déjà.
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.previousBalance", equalTo(0));

        givenAs(admin).contentType("application/json")
                .body("""
                        { "campaignId": "%s", "amount": 1806000,
                          "notes": "Reliquat de la campagne 2025-2026, hors outil" }
                        """.formatted(campaignId))
                .when().put("/api/v1/delegate-opening-balances/" + delegateId)
                .then().statusCode(200)
                .body("data.amount", equalTo(1806000));

        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.previousBalance", equalTo(1806000));

        // Le suivi détaillé part du même point : sinon les deux écrans
        // raconteraient deux campagnes différentes.
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId + "/ledger")
                .then().statusCode(200)
                .body("data.previousBalance", equalTo(1806000));
    }

    @Test
    void the_balance_is_corrected_in_place_and_never_stacked() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String delegateId = delegate(admin, "KOUI IBODE MARCELIN");

        for (int amount : new int[]{500_000, 750_000}) {
            givenAs(admin).contentType("application/json")
                    .body("{ \"campaignId\": \"%s\", \"amount\": %d }"
                            .formatted(campaignId, amount))
                    .when().put("/api/v1/delegate-opening-balances/" + delegateId)
                    .then().statusCode(200);
        }

        // Une seconde saisie corrige, elle n'ajoute pas : deux vérités
        // sur ce qu'un délégué doit rendraient l'état inexploitable.
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/delegate-opening-balances")
                .then().statusCode(200)
                .body("data.size()", equalTo(1))
                .body("data[0].amount", equalTo(750000));
        givenAs(admin).queryParam("campaignId", campaignId)
                .when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.previousBalance", equalTo(750000));
    }

    /**
     * Le suivi détaillé de tous les délégués tient dans un fichier.
     *
     * <p>Demandé le 12/09/2026 : l'export existait délégué par délégué,
     * et lire la campagne obligeait à empiler autant de fichiers que de
     * délégués.</p>
     */
    @Test
    void every_delegate_ledger_fits_in_one_file() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String first = delegate(admin, "SEHE KOUHOUSSOUI MICHEL");
        String second = delegate(admin, "KOUI IBODE MARCELIN");
        // Un solde d'ouverture n'est pas une opération : il faut de
        // vraies avances pour que le suivi ait des lignes.
        for (String id : new String[]{first, second}) {
            String advanceId = givenAs(admin).contentType("application/json")
                    .body("""
                            { "delegateSupplierId": "%s", "advanceDate": "%s",
                              "advanceAmount": 250000, "paymentMethod": "CHEQUE",
                              "campaignId": "%s" }
                            """.formatted(id, LocalDate.now(), campaignId))
                    .when().post("/api/v1/collector-advances").then().statusCode(201)
                    .extract().path("data.id");
            givenAs(admin).when().post("/api/v1/collector-advances/" + advanceId + "/approve")
                    .then().statusCode(200);
            givenAs(admin).contentType("application/json")
                    .body("{ \"acknowledgeInsufficientBalance\": true }")
                    .when().post("/api/v1/collector-advances/" + advanceId + "/disburse")
                    .then().statusCode(200);
        }

        String csv = givenAs(admin)
                .queryParam("campaignId", campaignId).queryParam("format", "csv")
                .when().get("/api/v1/collector-advances/delegates/ledger/export")
                .then().statusCode(200).extract().asString();

        // Chaque ligne dit de quel délégué elle parle : réunies sans
        // cela, les lignes de douze délégués seraient indiscernables.
        org.assertj.core.api.Assertions.assertThat(csv)
                .contains("SEHE KOUHOUSSOUI MICHEL")
                .contains("KOUI IBODE MARCELIN");
    }

    @Test
    void a_supplier_who_is_not_a_delegate_carries_no_opening_balance() {
        UserEntity admin = admin();
        String campaignId = campaign(admin);
        String supplierId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Quincaillerie du port\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");

        givenAs(admin).contentType("application/json")
                .body("{ \"campaignId\": \"%s\", \"amount\": 100000 }".formatted(campaignId))
                .when().put("/api/v1/delegate-opening-balances/" + supplierId)
                .then().statusCode(422);
    }
}
