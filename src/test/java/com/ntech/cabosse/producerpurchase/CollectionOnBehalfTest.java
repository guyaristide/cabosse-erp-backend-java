package com.ntech.cabosse.producerpurchase;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * La collecte comptabilisée en mandat, sans charge d'achat
 * (arbitré par l'expert-comptable le 29/09/2026).
 *
 * <p>Une coopérative n'achète pas à ses membres : elle avance l'argent,
 * expédie la matière et déduit sa commission du règlement qu'elle
 * reçoit. Porter la collecte en charge d'achat ferait apparaître au
 * compte de résultat un achat qui n'en est pas un, avec les
 * conséquences fiscales qui suivent.</p>
 *
 * <p>Couper purement et simplement l'écriture était impossible : la
 * pièce n'a que deux lignes, et retirer le débit la déséquilibre, ce que
 * le serveur refuse en annulant le reçu avec elle. La contrepartie est
 * donc déplacée, pas supprimée. C'est ce que tiennent ces tests : la
 * dette envers le producteur ne bouge pas, seule sa contrepartie
 * change de classe.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CollectionOnBehalfTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-mandat-" + TestFixtures.randomSlugSuffix(), "Coopérative Mandat");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Mandat";
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

    private record Refs(String memberId, String articleId, String siteId) {}

    private Refs referentials(UserEntity admin) {
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"SEHE\",\"firstName\":\"Michel\",\"gender\":\"MALE\","
                        + "\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
        String siteCode = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\""
                        + siteCode + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Fèves séchées\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        return new Refs(memberId, articleId, siteId);
    }

    private void setPrefs(UserEntity who, String body) {
        givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
    }

    private String receipt(UserEntity who, Refs refs) {
        return givenAs(who).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 200, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(),
                                refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.ref");
    }

    /** Les comptes de la pièce de collecte, pour les lire d'un coup. */
    private io.restassured.response.ValidatableResponse piece(UserEntity who, String ref) {
        return givenAs(who).when().get("/api/v1/accounting/journal?search=" + ref)
                .then().statusCode(200);
    }

    private static String accounts(String ref) {
        return "data.items.find { it.sourceType == 'PRODUCER_PURCHASE' }.entries.syscohadaAccount";
    }

    @Test
    void sans_le_mode_la_collecte_reste_une_charge_d_achat() {
        UserEntity a = admin();
        Refs refs = referentials(a);

        // Le cas général : une structure qui achète pour revendre tient
        // bien une charge. Le mode ne s'active pas tout seul.
        // La classe, pas le sous-compte : il se déduit de la nature de
        // l'article, 602 pour une matière première, 601 pour une
        // marchandise. Ce qui compte ici est qu'une charge existe.
        String ref = receipt(a, refs);
        piece(a, ref).body(accounts(ref), hasItem(startsWith("6")));
    }

    @Test
    void en_mandat_la_contrepartie_quitte_la_classe_6() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        setPrefs(a, "{ \"collectionOnBehalf\": true }");

        String ref = receipt(a, refs);

        // Aucune charge : c'est toute la demande. Et la dette envers le
        // producteur est intacte, sans quoi on ne saurait plus ce qu'on
        // lui doit.
        piece(a, ref)
                .body(accounts(ref), not(hasItem(startsWith("6"))))
                .body(accounts(ref), hasItem("471100"))
                .body(accounts(ref), hasItem(startsWith("401")));
    }

    @Test
    void le_compte_d_avance_appartient_a_la_structure() {
        UserEntity a = admin();
        Refs refs = referentials(a);

        // Chaque structure ouvre ses comptes dans son propre plan :
        // figer le numéro sur celui d'un client le rendrait inutilisable
        // pour les autres.
        setPrefs(a, "{ \"collectionOnBehalf\": true, \"collectionAdvanceAccount\": \"471200\" }");

        String ref = receipt(a, refs);
        piece(a, ref).body(accounts(ref), hasItem("471200"));
    }

    @Test
    void le_reglement_du_producteur_ne_change_pas() {
        UserEntity a = admin();
        Refs refs = referentials(a);
        setPrefs(a, "{ \"collectionOnBehalf\": true }");

        // Ce que la structure doit au producteur et la façon de le lui
        // payer ne relèvent pas du montage fiscal : le reçu se règle
        // comme avant, et le stock est entré comme avant.
        String ref = receipt(a, refs);
        givenAs(a).when()
                .get("/api/v1/stocks/" + refs.articleId() + "/sites/" + refs.siteId())
                .then().statusCode(200).body("data.quantity", equalTo(200));
        piece(a, ref).body(
                "data.items.find { it.sourceType == 'PRODUCER_PURCHASE' }.totalDebit",
                equalTo(200000));
    }
}
