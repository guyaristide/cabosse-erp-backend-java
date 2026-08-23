package com.ntech.cabosse.accounting;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Déclaration de TVA mensuelle : les montants déclarés doivent sortir des
 * livres, pas d'une saisie libre.
 *
 * <p>Ce qui est déclaré à l'administration fiscale engage la coopérative.
 * Le workflow fige donc, au moment du « prêt à déposer », la TVA collectée
 * et déductible telles que le journal les porte. Si ce calcul dévie ou si
 * le statut ne se persiste plus, la déclaration du mois suivant repart de
 * chiffres faux sans que personne ne s'en aperçoive avant le contrôle.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TvaWorkflowTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-tva-" + TestFixtures.randomSlugSuffix(), "Coopérative TVA");
        migrations.runMigrationsFor(tenant.databaseName);

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

    /** OD validée : seule une pièce au journal compte pour la déclaration. */
    private void postValidatedOd(UserEntity admin, String body) {
        String id = givenAs(admin).contentType("application/json").body(body)
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    /** Une vente TTC : 10 000 HT + 1 800 de TVA collectée. */
    private void saleWithVat(UserEntity admin) {
        postValidatedOd(admin, """
                { "date": "%s", "libelle": "Vente TTC",
                  "lines": [
                    { "account": "411000", "libelle": "Client", "debitFcfa": 11800 },
                    { "account": "701000", "libelle": "Vente", "creditFcfa": 10000 },
                    { "account": "445700", "libelle": "TVA collectée", "creditFcfa": 1800 }
                  ] }
                """.formatted(LocalDate.now()));
    }

    /** Un achat TTC : 5 000 HT + 900 de TVA déductible. */
    private void purchaseWithVat(UserEntity admin) {
        postValidatedOd(admin, """
                { "date": "%s", "libelle": "Achat TTC",
                  "lines": [
                    { "account": "601000", "libelle": "Achat", "debitFcfa": 5000 },
                    { "account": "445600", "libelle": "TVA déductible", "debitFcfa": 900 },
                    { "account": "401000", "libelle": "Fournisseur", "creditFcfa": 5900 }
                  ] }
                """.formatted(LocalDate.now()));
    }

    @Test
    void la_declaration_fige_les_montants_du_journal() {
        UserEntity admin = tenantAdmin();
        saleWithVat(admin);
        purchaseWithVat(admin);
        String month = YearMonth.now().toString();

        // Prêt à déposer : collectée 1 800, déductible 900, solde 900. Ces
        // montants viennent du journal, pas d'une saisie : c'est le point.
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/tva/" + month + "/mark-ready")
                .then().statusCode(200)
                .body("data.status", equalTo("PRET_A_DEPOSER"))
                .body("data.collectedFcfa", equalTo(1800.0F))
                .body("data.deductibleFcfa", equalTo(900.0F))
                .body("data.toPayFcfa", equalTo(900.0F))
                .body("data.dueDate", notNullValue());

        // Persisté : l'historique du workflow doit le restituer tel quel.
        // Le DTO d'historique identifie le mois par periodStart, pas par
        // le libellé humain.
        String firstDay = YearMonth.now().atDay(1).toString();
        givenAs(admin)
                .when().get("/api/v1/accounting/tva/history")
                .then().statusCode(200)
                .body("data.periodStart", hasItem(firstDay))
                .body("data.find { it.periodStart == '" + firstDay + "' }.status",
                        equalTo("PRET_A_DEPOSER"))
                .body("data.find { it.periodStart == '" + firstDay + "' }.toPayFcfa",
                        equalTo(900.0F));
    }

    @Test
    void le_depot_conserve_le_numero_et_l_etat() {
        UserEntity admin = tenantAdmin();
        saleWithVat(admin);
        String month = YearMonth.now().toString();

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/tva/" + month + "/mark-ready")
                .then().statusCode(200);

        // Le dépôt trace le numéro remis par l'administration : c'est la
        // preuve que la coopérative produira en cas de contrôle.
        givenAs(admin).contentType("application/json")
                .body("""
                        { "depositedNumber": "DGI-2026-00042", "depositedAt": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/accounting/tva/" + month + "/mark-deposed")
                .then().statusCode(200)
                .body("data.status", equalTo("DEPOSE"))
                .body("data.depositedNumber", equalTo("DGI-2026-00042"));

        String firstDay = YearMonth.now().atDay(1).toString();
        givenAs(admin)
                .when().get("/api/v1/accounting/tva/history")
                .then().statusCode(200)
                .body("data.find { it.periodStart == '" + firstDay + "' }.status", equalTo("DEPOSE"));
    }

    @Test
    void un_mois_sans_tva_se_declare_a_zero() {
        UserEntity admin = tenantAdmin();
        String month = YearMonth.now().toString();

        // Un mois creux se déclare quand même : néant, pas erreur. Refuser
        // ici forcerait une écriture bidon pour débloquer le workflow.
        // Le type JSON d'un zéro varie selon la sérialisation : on compare
        // des valeurs, pas des représentations.
        io.restassured.path.json.JsonPath body = givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/tva/" + month + "/mark-ready")
                .then().statusCode(200)
                .extract().jsonPath();
        for (String field : new String[] { "collectedFcfa", "deductibleFcfa", "toPayFcfa" }) {
            Number v = body.get("data." + field);
            org.junit.jupiter.api.Assertions.assertEquals(0.0, v.doubleValue(), 0.001,
                    "Un mois sans TVA doit se déclarer à zéro (" + field + ")");
        }
    }
}
