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
                    { "account": "411000", "libelle": "Client", "debit": 11800 },
                    { "account": "701000", "libelle": "Vente", "credit": 10000 },
                    { "account": "445700", "libelle": "TVA collectée", "credit": 1800 }
                  ] }
                """.formatted(LocalDate.now()));
    }

    /** Un achat TTC : 5 000 HT + 900 de TVA déductible. */
    private void purchaseWithVat(UserEntity admin) {
        postValidatedOd(admin, """
                { "date": "%s", "libelle": "Achat TTC",
                  "lines": [
                    { "account": "601000", "libelle": "Achat", "debit": 5000 },
                    { "account": "445600", "libelle": "TVA déductible", "debit": 900 },
                    { "account": "401000", "libelle": "Fournisseur", "credit": 5900 }
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
                .body("data.collected", equalTo(1800.0F))
                .body("data.deductible", equalTo(900.0F))
                .body("data.toPay", equalTo(900.0F))
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
                .body("data.find { it.periodStart == '" + firstDay + "' }.toPay",
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
        for (String field : new String[] { "collected", "deductible", "toPay" }) {
            Number v = body.get("data." + field);
            org.junit.jupiter.api.Assertions.assertEquals(0.0, v.doubleValue(), 0.001,
                    "Un mois sans TVA doit se déclarer à zéro (" + field + ")");
        }
    }

    /**
     * Le compte de TVA collectée se paramètre, comme son pendant
     * déductible.
     *
     * <p>Il était gravé dans le code : le moteur créditait 445700 et la
     * déclaration lisait 445700, d'accord entre eux mais sourds au plan
     * comptable du tenant. Depuis que celui-ci s'édite, une structure qui
     * renumérote sa TVA voyait ses ventes continuer d'alimenter un compte
     * qu'elle n'a plus, et sa déclaration le suivre : deux erreurs qui se
     * confirment l'une l'autre.</p>
     */
    @Test
    void le_compte_de_tva_collectee_suit_le_plan_du_tenant() {
        UserEntity admin = tenantAdmin();

        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"445710\", \"label\": \"TVA collectée 18 %\", \"family\": \"TVA\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("{ \"vatCollectedAccount\": \"445710\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        // Une vente passée sur le nouveau compte.
        postValidatedOd(admin, """
                { "date": "%s", "libelle": "Vente TTC",
                  "lines": [
                    { "account": "411000", "libelle": "Client", "debit": 11800 },
                    { "account": "701000", "libelle": "Vente", "credit": 10000 },
                    { "account": "445710", "libelle": "TVA collectée", "credit": 1800 }
                  ] }
                """.formatted(LocalDate.now()));

        // La déclaration la voit : elle lit le compte du tenant, pas une
        // constante.
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/tva/" + YearMonth.now() + "/mark-ready")
                .then().statusCode(200)
                .body("data.collected", org.hamcrest.Matchers.comparesEqualTo(1800.0f));
    }

    /**
     * Les pièces déjà passées sur l'ancien compte continuent de compter :
     * changer de compte ne doit pas effacer une déclaration en cours.
     */
    @Test
    void les_ecritures_de_l_ancien_compte_restent_declarees() {
        UserEntity admin = tenantAdmin();
        saleWithVat(admin);

        givenAs(admin).contentType("application/json")
                .body("{ \"number\": \"445710\", \"label\": \"TVA collectée 18 %\", \"family\": \"TVA\" }")
                .when().post("/api/v1/accounting/chart").then().statusCode(201);
        givenAs(admin).contentType("application/json")
                .body("{ \"vatCollectedAccount\": \"445710\" }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/tva/" + YearMonth.now() + "/mark-ready")
                .then().statusCode(200)
                .body("data.collected", org.hamcrest.Matchers.comparesEqualTo(1800.0f));
    }
}
