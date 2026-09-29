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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Le titre de la pièce d'une livraison nomme à qui elle est due
 * (demandé le 29/09/2026).
 *
 * <p>Il disait « Achat producteur » suivi de la seule référence. Sur
 * l'écran des réceptions, une campagne entière défile en lignes que rien
 * ne distingue : pour savoir de qui parlait une pièce, il fallait la
 * déplier et lire sa ligne de crédit, qui elle porte le nom depuis
 * toujours.</p>
 *
 * <p>Le nom retenu est celui du bénéficiaire de la dette, donc le
 * délégué quand la livraison passe par lui. C'est à lui que la
 * coopérative doit l'argent, et c'est sous son nom que la pièce se
 * retrouve.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ReceiptPieceTitleTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-titre-" + TestFixtures.randomSlugSuffix(), "Coopérative Titres");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Titres";
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
                .body("{\"lastName\":\"KOUHOUSSOUI\",\"firstName\":\"Michel\",\"gender\":\"MALE\","
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

    @Test
    void le_titre_porte_la_livraison_et_son_beneficiaire() {
        UserEntity a = admin();
        Refs refs = referentials(a);

        String ref = givenAs(a).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": 200, "guaranteedPricePerKg": 1000, "paymentMethod": "CASH" }
                        """.formatted(LocalDate.now(), refs.memberId(), refs.articleId(),
                                refs.siteId()))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.ref");

        // Le titre se lit sans rien déplier : la référence, et le nom.
        givenAs(a).when().get("/api/v1/accounting/journal?search=" + ref)
                .then().statusCode(200)
                .body("data.items.find { it.sourceType == 'PRODUCER_PURCHASE' }.libelle",
                        containsString("Livraison " + ref))
                .body("data.items.find { it.sourceType == 'PRODUCER_PURCHASE' }.libelle",
                        containsString("KOUHOUSSOUI"))
                // « Achat » a disparu du titre : la coopérative collecte
                // pour ses membres, le mot n'était pas le bon.
                .body("data.items.find { it.sourceType == 'PRODUCER_PURCHASE' }.libelle",
                        not(containsString("Achat producteur")));
    }
}
