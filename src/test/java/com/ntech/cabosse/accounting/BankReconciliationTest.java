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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Rapprochement bancaire : le relevé de la banque contre les livres.
 *
 * <p>C'est le contrôle qui détecte un encaissement jamais comptabilisé ou
 * une sortie de banque que personne n'a saisie, c'est-à-dire les deux
 * visages d'un détournement. L'auto-rapprochement ne doit apparier une
 * ligne que lorsqu'il n'y a aucun doute : un seul candidat, même montant,
 * même sens, à trois jours près. Dans le doute, il laisse l'humain
 * trancher, car un faux appariement masque exactement ce que le
 * rapprochement existe pour révéler.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class BankReconciliationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity admin;
    private String bankAccountId;

    private void setUpBank() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rapp-" + TestFixtures.randomSlugSuffix(), "Coopérative Rapprochement");
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
        admin = u;

        bankAccountId = givenAs(admin).contentType("application/json")
                .body("""
                        { "bankName": "Banque Atlantique", "accountNumber": "CI-0001",
                          "syscohadaAccount": "521000", "label": "Compte courant",
                          "kind": "BANQUE" }
                        """)
                .when().post("/api/v1/accounting/bank-accounts").then().statusCode(201)
                .extract().path("data.id");
    }

    /** Encaissement au journal : débit banque, crédit ventes. */
    private void postBankReceipt(int amount, LocalDate date) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Encaissement client",
                          "lines": [
                            { "account": "521000", "libelle": "Banque", "debitFcfa": %d },
                            { "account": "701000", "libelle": "Vente", "creditFcfa": %d }
                          ] }
                        """.formatted(date, amount, amount))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    /** Import multipart d'un relevé CSV (date;libellé;montant;sens). */
    private JsonPath importStatement(String csv) {
        return givenAs(admin)
                .multiPart("bankAccountId", bankAccountId)
                .multiPart("file", "releve.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post("/api/v1/accounting/bank-statements/import")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    private JsonPath lines(String statementId) {
        return givenAs(admin)
                .when().get("/api/v1/accounting/bank-statements/" + statementId + "/lines")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    @Test
    void une_ligne_sans_ambiguite_s_apparie_toute_seule_a_trois_jours_pres() {
        setUpBank();
        // L'écriture est datée d'avant-hier, le relevé d'aujourd'hui : le
        // délai bancaire ordinaire, couvert par la fenêtre de trois jours.
        postBankReceipt(250000, LocalDate.now().minusDays(2));

        JsonPath imported = importStatement(
                LocalDate.now() + ";VIREMENT CLIENT;250000;CREDIT\n");
        assertEqualsInt(1, imported.getInt("data.linesInserted"));
        assertEqualsInt(1, imported.getInt("data.autoMatched"));

        String statementId = imported.getString("data.statementId");
        givenAs(admin)
                .when().get("/api/v1/accounting/bank-statements/" + statementId + "/lines")
                .then().statusCode(200)
                .body("data[0].status", equalTo("MATCHED"))
                .body("data[0].matchedPieceId", notNullValue());
    }

    @Test
    void deux_candidats_de_meme_montant_laissent_l_humain_trancher() {
        setUpBank();
        // Deux encaissements identiques dans la fenêtre : apparier au
        // hasard masquerait celui qui manque peut-être en banque.
        postBankReceipt(100000, LocalDate.now().minusDays(1));
        postBankReceipt(100000, LocalDate.now().minusDays(2));

        JsonPath imported = importStatement(
                LocalDate.now() + ";VIREMENT;100000;CREDIT\n");
        assertEqualsInt(0, imported.getInt("data.autoMatched"));

        String statementId = imported.getString("data.statementId");
        String lineId = lines(statementId).getString("data[0].id");

        // Les deux candidats sont proposés, et l'appariement manuel tient.
        JsonPath candidates = givenAs(admin)
                .when().get("/api/v1/accounting/bank-statements/lines/" + lineId + "/candidates")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEqualsInt(2, candidates.getList("data").size());
        String pieceId = candidates.getString("data[0].id");

        givenAs(admin).contentType("application/json")
                .body("{\"pieceId\":\"" + pieceId + "\"}")
                .when().post("/api/v1/accounting/bank-statements/lines/" + lineId + "/match")
                .then().statusCode(200)
                .body("data.status", equalTo("MATCHED"));

        // Le dé-rapprochement ramène la ligne à l'état neutre, sans trace
        // résiduelle d'appariement.
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/bank-statements/lines/" + lineId + "/unmatch")
                .then().statusCode(200)
                .body("data.status", equalTo("UNMATCHED"))
                .body("data.matchedPieceId", nullValue());
    }

    @Test
    void hors_fenetre_de_trois_jours_rien_ne_s_apparie() {
        setUpBank();
        // Même montant, mais dix jours d'écart : hors fenêtre. Un
        // apparienment aussi lointain doit rester une décision humaine.
        postBankReceipt(300000, LocalDate.now().minusDays(10));

        JsonPath imported = importStatement(
                LocalDate.now() + ";VIREMENT ANCIEN;300000;CREDIT\n");
        assertEqualsInt(0, imported.getInt("data.autoMatched"));
    }

    @Test
    void ignorer_une_ligne_est_un_etat_pas_une_suppression() {
        setUpBank();
        JsonPath imported = importStatement(
                LocalDate.now() + ";FRAIS TENUE DE COMPTE;5000;DEBIT\n");
        String statementId = imported.getString("data.statementId");
        String lineId = lines(statementId).getString("data[0].id");

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/bank-statements/lines/" + lineId + "/ignore")
                .then().statusCode(200)
                .body("data.status", equalTo("IGNORED"));

        // La ligne reste au relevé : un rapprochement doit pouvoir prouver
        // ce qu'il a choisi de ne pas traiter.
        assertEqualsInt(1, lines(statementId).getList("data").size());
    }

    @Test
    void reimporter_le_meme_releve_est_refuse_sans_creer_de_doublon() {
        setUpBank();
        String csv = LocalDate.now() + ";VIREMENT CLIENT;80000;CREDIT\n"
                + LocalDate.now() + ";VIREMENT CLIENT B;45000;CREDIT\n";
        importStatement(csv);

        // Le même fichier repasse (double clic, reprise après incident) :
        // chaque doublon serait une ligne à pointer qui n'existe pas en
        // banque. L'import refuse net plutôt que de créer un relevé vide.
        givenAs(admin)
                .multiPart("bankAccountId", bankAccountId)
                .multiPart("file", "releve.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post("/api/v1/accounting/bank-statements/import")
                .then().statusCode(422);

        // Si une seule ligne est nouvelle, elle entre, et elle seule.
        String extended = csv + LocalDate.now() + ";FRAIS;3000;DEBIT\n";
        JsonPath third = importStatement(extended);
        assertEqualsInt(1, third.getInt("data.linesInserted"));
        assertEqualsInt(2, third.getInt("data.duplicatesSkipped"));
    }

    private static void assertEqualsInt(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
