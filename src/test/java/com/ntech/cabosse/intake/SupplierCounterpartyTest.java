package com.ntech.cabosse.intake;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Le reçu repose sur le fournisseur quand le document ne nomme personne.
 *
 * <p>Les extraits du système national de traçabilité arrivent du terrain
 * avec la colonne « Producteur » vide : seul l'apporteur est connu. La
 * comptabilisation refusait alors chaque ligne, et un bordereau dont
 * toutes les lignes sont refusées est refusé en entier. Tout le stock
 * reçu au magasin restait ainsi hors du système (expert, 12/10/2026).</p>
 *
 * <p>La coopérative achète aussi bien à un délégué collecteur qu'à un
 * fournisseur ordinaire, sociétaire ou non : le reçu se pose sur celui
 * que le bordereau nomme, et c'est son compte que le règlement solde.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupplierCounterpartyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-appo-" + TestFixtures.randomSlugSuffix(), "Coopérative Apporteurs");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);

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
        fundCashBox(u, 20_000_000);

        // Sans paiement fractionné, une livraison est réputée soldée au
        // reçu : c'est ce réglage qui ouvre l'échéancier.
        givenAs(u).contentType("application/json")
                .body("{\"producerPartialPaymentEnabled\":true}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return u;
    }

    private String site(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"mag-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String article(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String supplier(UserEntity admin, String name, boolean collector) {
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"f-" + TestFixtures.randomSlugSuffix() + "\",\"name\":\"" + name
                        + "\",\"collector\":" + collector + "}")
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    @Test
    void un_extrait_sans_producteur_se_comptabilise_sur_le_fournisseur_du_bordereau() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String siteId = site(admin);
        String articleId = article(admin);
        // Un fournisseur ordinaire : ni délégué collecteur, ni sociétaire.
        String supplierId = supplier(admin, "NEGOCE DU SUD", false);

        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "productLabel": "Cacao", "date": "%s",
                            "movement": "Entrée Stock", "ref": "BR0158", "truckNumber": "CJY879",
                            "supplierName": "NEGOCE DU SUD", "lineNumber": "1",
                            "grossWeightKg": "866", "bagCount": "14", "netWeightKg": "852" } ]
                        """.formatted(today))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1));

        // Le bordereau reconnaît son fournisseur, sans en faire un délégué :
        // la qualité de délégué vit sur la fiche, pas sur le bordereau.
        String noteId = givenAs(admin).when().get("/api/v1/intake-notes?status=TO_ACCOUNT")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].delegateSupplierId", nullValue())
                .extract().path("data[0].id");

        // L'extrait du jour : poids, montant, délégué du fichier, et rien
        // d'autre. C'est la forme exacte des fichiers du terrain.
        String lines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "date": "%s", "nbSacs": "14", "weightKg": "852",
                    "amount": "1022400", "amountCard": "0", "amountCash": "1022400" } ] }
                """.formatted(articleId, siteId, today);

        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/preview")
                .then().statusCode(200)
                .body("data.invalidRows", equalTo(0))
                .body("data.rowsOnSupplier", equalTo(1))
                .body("data.membersToCreate", equalTo(0))
                .body("data.supplierName", equalTo("NEGOCE DU SUD"))
                .body("data.rows[0].issues[0]", containsString("NEGOCE DU SUD"));

        givenAs(admin).contentType("application/json").body(lines)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/commit")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(1))
                .body("data.createdMembers", equalTo(0));

        // Aucune fiche de sociétaire fabriquée au passage : le registre
        // des membres compte des personnes, pas des trous dans un fichier.
        givenAs(admin).when().get("/api/v1/members")
                .then().statusCode(200).body("data.total", equalTo(0));

        String purchaseId = givenAs(admin).when().get("/api/v1/producer-purchases")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].memberId", nullValue())
                .body("data.items[0].supplierId", equalTo(supplierId))
                .body("data.items[0].counterpartyName", equalTo("NEGOCE DU SUD"))
                .extract().path("data.items[0].id");

        // La matière est entrée en stock et la pièce est passée : c'est
        // tout l'objet de la levée du verrou.
        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                // Les sacs du bordereau suivent jusqu'au reçu : sans eux
                // la fiche du jour perdait son compte (expert, 13/10/2026).
                .body("data.nbSacs", equalTo(14))
                .body("data.weightKg", equalTo(852))
                .body("data.pieceRef", notNullValue())
                .body("data.movementRef", notNullValue());
    }

    @Test
    void completer_un_bordereau_ne_recree_pas_les_lignes_sans_numero() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String siteId = site(admin);
        String articleId = article(admin);
        supplier(admin, "NEGOCE DU SUD", false);

        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "date": "%s", "ref": "BR0160",
                            "supplierName": "NEGOCE DU SUD", "netWeightKg": "852" } ]
                        """.formatted(today))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200);
        String noteId = givenAs(admin).when().get("/api/v1/intake-notes?status=TO_ACCOUNT")
                .then().statusCode(200).extract().path("data[0].id");

        // Deux lignes sans numéro de carnet, dont deux identiques : c'est
        // la forme ordinaire des extraits du terrain.
        String lines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "date": "%s", "weightKg": "426", "amount": "511200" },
                  { "rowNumber": 3, "date": "%s", "weightKg": "426", "amount": "511200" } ] }
                """.formatted(articleId, siteId, today, today);

        givenAs(admin).contentType("application/json").body(lines)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/commit")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(2));

        // Rejouer le même fichier par « Compléter » ne doit rien
        // recréer : sans numéro officiel, deux lignes identiques se
        // reconnaissent à leur date, leur poids et leur montant.
        givenAs(admin).contentType("application/json").body(lines)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/complete")
                .then().statusCode(422);

        givenAs(admin).when().get("/api/v1/producer-purchases")
                .then().statusCode(200).body("data.total", equalTo(2));
    }

    @Test
    void un_fournisseur_inconnu_n_interdit_plus_de_comptabiliser() {
        UserEntity admin = admin();
        LocalDate today = LocalDate.now();
        String siteId = site(admin);
        String articleId = article(admin);
        givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"Zale Issouf\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201);

        // Le bordereau nomme un apporteur que le référentiel des
        // fournisseurs ne connaît pas : la coopérative achète aussi à
        // des producteurs, membres ou non (expert, 13/10/2026).
        givenAs(admin).contentType("application/json")
                .body("""
                        [ { "rowNumber": 2, "date": "%s", "ref": "BR0878",
                            "supplierName": "APPORTEUR INCONNU", "netWeightKg": "52" } ]
                        """.formatted(today))
                .when().post("/api/v1/intake-notes/import/commit?siteId=" + siteId)
                .then().statusCode(200);
        String noteId = givenAs(admin).when().get("/api/v1/intake-notes?status=TO_ACCOUNT")
                .then().statusCode(200).extract().path("data[0].id");

        String lines = """
                { "articleId": "%s", "siteId": "%s", "lines": [
                  { "rowNumber": 2, "date": "%s", "nbSacs": "1", "weightKg": "52",
                    "amount": "62400", "producerName": "Zale Issouf" } ] }
                """.formatted(articleId, siteId, today);

        // L'avertissement reste, le refus part : le reçu se pose sur le
        // producteur que le fichier nomme.
        givenAs(admin).contentType("application/json").body(lines)
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/preview")
                .then().statusCode(200)
                .body("data.supplierUnmatched", equalTo(true))
                .body("data.invalidRows", equalTo(0));

        givenAs(admin).contentType("application/json").body(lines)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/intake-notes/" + noteId + "/accounting/commit")
                .then().statusCode(200)
                .body("data.createdReceipts", equalTo(1));
    }

    @Test
    void ce_que_la_cooperative_doit_a_un_fournisseur_apporteur_se_regle() {
        UserEntity admin = admin();
        String siteId = site(admin);
        String articleId = article(admin);
        String supplierId = supplier(admin, "COMPTOIR DE MEAGUI", false);

        String purchaseId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "supplierId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 1000, "guaranteedPricePerKg": 900,
                          "paymentMethod": "BANK_TRANSFER", "amountPaid": 0 }
                        """.formatted(LocalDate.now(), supplierId, articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");

        // L'échéancier le nomme, au lieu de fondre tous les reçus sans
        // producteur en un bénéficiaire sans nom que rien ne peut payer.
        givenAs(admin).when().get("/api/v1/producer-payments/outstanding")
                .then().statusCode(200)
                .body("data.totalRemaining", equalTo(900000))
                .body("data.beneficiaries", hasSize(1))
                .body("data.beneficiaries[0].kind", equalTo("SUPPLIER"))
                .body("data.beneficiaries[0].supplierId", equalTo(supplierId))
                .body("data.beneficiaries[0].name", equalTo("COMPTOIR DE MEAGUI"));

        // Et la file à payer le sort sous sa propre nature : le caissier
        // paie une personne, il doit lire laquelle.
        givenAs(admin).when().get("/api/v1/treasury/payables?kind=SUPPLIER_PURCHASE")
                .then().statusCode(200)
                .body("data.page.items", hasSize(1))
                .body("data.page.items[0].beneficiaryName", equalTo("COMPTOIR DE MEAGUI"));

        givenAs(admin).contentType("application/json")
                .body("""
                        { "supplierId": "%s", "paymentMethod": "BANK_TRANSFER", "date": "%s",
                          "allocations": [ { "purchaseId": "%s", "amount": 900000 } ] }
                        """.formatted(supplierId, LocalDate.now(), purchaseId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-payments").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/producer-purchases/" + purchaseId)
                .then().statusCode(200)
                .body("data.remainder", equalTo(0));
        givenAs(admin).when().get("/api/v1/producer-payments/outstanding")
                .then().statusCode(200)
                .body("data.beneficiaries", hasSize(0));
    }

    @Test
    void un_recu_sans_producteur_ni_fournisseur_n_a_personne_a_payer() {
        UserEntity admin = admin();
        String siteId = site(admin);
        String articleId = article(admin);

        givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 100, "guaranteedPricePerKg": 900,
                          "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), articleId, siteId))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(422);
    }
}
