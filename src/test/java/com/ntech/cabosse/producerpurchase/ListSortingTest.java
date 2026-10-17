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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.contains;

/**
 * Trier une liste depuis l'écran.
 *
 * <p>Jusqu'ici chaque endpoint imposait son ordre et renvoyait un tri
 * qu'il n'avait pas appliqué : la barre d'état d'une liste ne pouvait
 * donc rien en dire. L'écran demande désormais sa colonne, et ce qui
 * revient est le tri réellement appliqué.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ListSortingTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-tri-" + TestFixtures.randomSlugSuffix(), "Coopérative Tri");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tri";
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

    /**
     * La caisse, approvisionnée à la date du jour.
     *
     * <p>L'aide partagée date son écriture d'un an : ici les reçus sont
     * du jour, et le solde de caisse se lit à leur date.</p>
     */
    private void fundToday(UserEntity admin) {
        String odId = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Solde d'ouverture de caisse",
                          "lines": [
                            { "account": "571000", "libelle": "Espèces", "debit": 50000000 },
                            { "account": "471000", "libelle": "Contrepartie", "credit": 50000000 } ] }
                        """.formatted(LocalDate.now().minusDays(1)))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).when().post("/api/v1/accounting/od/" + odId + "/validate")
                .then().statusCode(200);
    }

    private void member(UserEntity admin, String last) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "%s", "gender": "MALE", "status": "ACTIVE" }
                        """.formatted(last))
                .when().post("/api/v1/members").then().statusCode(201);
    }

    @Test
    void la_liste_se_trie_sur_la_colonne_demandee() {
        UserEntity admin = admin();
        for (String name : List.of("ZOTIA", "ABOU", "MIAN")) member(admin, name);

        // Sans demande, l'ordre que la liste a toujours eu.
        givenAs(admin).when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.sorts", contains("name"))
                .body("data.order", equalTo("asc"))
                .body("data.items.name", contains("ABOU", "MIAN", "ZOTIA"));

        givenAs(admin).queryParam("sort", "name").queryParam("direction", "desc")
                .when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.order", equalTo("desc"))
                .body("data.items.name", contains("ZOTIA", "MIAN", "ABOU"));
    }

    @Test
    void une_colonne_non_triable_rend_la_liste_quand_meme() {
        UserEntity admin = admin();
        member(admin, "ABOU");

        // Ni erreur ni tri sauvage : la liste s'affiche dans son ordre
        // par défaut, et l'enveloppe dit lequel. Refuser casserait
        // l'écran entier pour une colonne disparue.
        givenAs(admin).queryParam("sort", "notes").queryParam("direction", "desc")
                .when().get("/api/v1/members")
                .then().statusCode(200)
                .body("data.sorts", contains("name"))
                .body("data.order", equalTo("asc"));
    }

    @Test
    void le_tri_des_recus_suit_la_colonne_et_son_sens() {
        UserEntity admin = admin();
        fundToday(admin);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"mag-"
                        + TestFixtures.randomSlugSuffix() + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        String articleId = givenAs(admin).contentType("application/json")
                .body("{\"type\":\"MERCHANDISE\",\"name\":\"Fèves\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
        String memberId = givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"KELA\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");

        for (int weight : new int[]{ 300, 100, 200 }) {
            givenAs(admin).contentType("application/json")
                    .body("""
                            { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                              "weightKg": %d, "guaranteedPricePerKg": 1200, "paymentMethod": "CASH" }
                            """.formatted(LocalDate.now(), memberId, articleId, siteId, weight))
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .when().post("/api/v1/producer-purchases").then().statusCode(201);
        }

        givenAs(admin).queryParam("sort", "weightKg").queryParam("direction", "asc")
                .when().get("/api/v1/producer-purchases")
                .then().statusCode(200)
                .body("data.sorts", contains("weightKg"))
                .body("data.order", equalTo("asc"))
                .body("data.items.weightKg", contains(100, 200, 300));
    }
}
