package com.ntech.cabosse.accounting;

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
import static org.hamcrest.Matchers.notNullValue;

/**
 * Extourner une pièce du journal, à la main.
 *
 * <p>Une pièce versée au journal ne se modifie pas : elle s'annule par
 * une autre écriture. Le moteur savait contre-passer quand une
 * opération métier était annulée, mais une OD validée à tort n'avait
 * aucune issue (demandé le 06/10/2026).</p>
 *
 * <p>En montants négatifs, et non par inversion débit/crédit : chaque
 * ligne garde son compte et son sens, le montant change de signe, et le
 * grand-livre porte la correction sur la même colonne que l'écriture
 * d'origine.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ManualReversalTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-ext-" + TestFixtures.randomSlugSuffix(), "Coopérative Extourne");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Extourne";
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

    /** Une OD validée, qui a donc sa pièce au journal. */
    private String postedPieceId(UserEntity admin) {
        // Les comptes de l'OD doivent exister au plan : l'écran les
        // ouvre au passage, le test fait de même.
        for (String[] account : new String[][]{
                {"601000", "Achats de marchandises"}, {"401100", "Fournisseurs"}}) {
            givenAs(admin).contentType("application/json")
                    .body("{\"number\":\"%s\",\"label\":\"%s\"}"
                            .formatted(account[0], account[1]))
                    .when().post("/api/v1/accounting/chart");
        }
        String odId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Loyer du mois",
                          "lines": [
                            { "account": "601000", "libelle": "Loyer", "debit": 250000 },
                            { "account": "401100", "libelle": "Fournisseur", "credit": 250000 } ] }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().log().ifValidationFails().statusCode(200)
                .body("data.pieceRef", notNullValue());

        return givenAs(admin)
                .when().get("/api/v1/accounting/journal?sourceType=MANUAL_ENTRY")
                .then().statusCode(200)
                .extract().path("data.items[0].id");
    }

    @Test
    void l_extourne_reprend_les_memes_lignes_en_negatif() {
        UserEntity admin = admin();
        String pieceId = postedPieceId(admin);

        var reversal = givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Loyer imputé deux fois\"}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(201)
                .body("data.libelle", org.hamcrest.Matchers.containsString("Loyer imputé deux fois"))
                .extract().jsonPath();

        // Le compte et le sens ne bougent pas : seul le signe change.
        // L'inversion débit/crédit aurait gonflé les deux colonnes du
        // grand-livre de la même somme.
        org.assertj.core.api.Assertions.assertThat(
                reversal.getString("data.entries[0].syscohadaAccount")).isEqualTo("601000");
        org.assertj.core.api.Assertions.assertThat(
                new java.math.BigDecimal(reversal.getString("data.entries[0].debit")))
                .isEqualByComparingTo("-250000");
        org.assertj.core.api.Assertions.assertThat(
                new java.math.BigDecimal(reversal.getString("data.entries[1].credit")))
                .isEqualByComparingTo("-250000");
        org.assertj.core.api.Assertions.assertThat(
                reversal.getString("data.reversedFromPieceId")).isEqualTo(pieceId);
    }

    @Test
    void une_piece_ne_s_extourne_qu_une_fois() {
        UserEntity admin = admin();
        String pieceId = postedPieceId(admin);

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Erreur de compte\"}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(201);

        // Un second clic doublerait l'annulation et rendrait le compte
        // débiteur de ce qu'il n'a jamais porté.
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Encore\"}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(422);
    }

    @Test
    void une_extourne_ne_s_extourne_pas() {
        UserEntity admin = admin();
        String pieceId = postedPieceId(admin);

        String reversalId = givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Erreur de compte\"}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(201).extract().path("data.id");

        // La paire se lit à deux ; une chaîne ne se lit plus.
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Finalement si\"}")
                .when().post("/api/v1/accounting/journal/" + reversalId + "/reverse")
                .then().statusCode(422);
    }

    @Test
    void l_extourne_exige_sa_raison() {
        UserEntity admin = admin();
        String pieceId = postedPieceId(admin);

        // Elle reste au journal avec la pièce : une annulation sans
        // motif ne s'explique plus six mois après.
        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(422);
    }

    @Test
    void le_grand_livre_revient_a_zero_apres_extourne() {
        UserEntity admin = admin();
        String pieceId = postedPieceId(admin);

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Loyer imputé deux fois\"}")
                .when().post("/api/v1/accounting/journal/" + pieceId + "/reverse")
                .then().statusCode(201);

        // 250 000 au débit puis −250 000 : les deux pièces s'annulent,
        // et c'est tout l'objet de l'extourne. Avec une inversion
        // débit/crédit, le compte aurait porté 500 000 de mouvement
        // pour une opération qui n'a rien laissé.
        var journal = givenAs(admin)
                .when().get("/api/v1/accounting/journal")
                .then().statusCode(200).extract().jsonPath();
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (Object raw : journal.getList("data.items")) {
            @SuppressWarnings("unchecked")
            var item = (java.util.Map<String, Object>) raw;
            sum = sum.add(new java.math.BigDecimal(String.valueOf(item.get("totalDebit"))));
        }
        org.assertj.core.api.Assertions.assertThat(sum).isEqualByComparingTo("0");
    }
}
