package com.ntech.cabosse.members;

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

/**
 * L'écriture de part sociale, et les deux portes qu'elle ne franchit pas
 * de la même façon (signalé le 27/09/2026).
 *
 * <p>Le réglage promet une pièce à la validation d'une adhésion. Il est
 * bien honoré à la création et à l'approbation. L'import de masse, lui,
 * en est exempt <strong>délibérément</strong> : un fichier repris décrit
 * des adhésions passées, dont l'argent a été encaissé il y a des années
 * et dépensé depuis. Créditer la caisse à l'import fabriquerait des
 * espèces imaginaires, membre par membre, et les soldes réels entrent
 * par les à-nouveaux.</p>
 *
 * <p>Cette exception était juste mais muette : ni le réglage ni l'écran
 * d'import ne la mentionnaient, et on ne pouvait que la découvrir en
 * constatant l'absence de pièce. Ce test la tient dans les deux sens,
 * pour qu'elle ne bascule pas en silence.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberCapitalEntryTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-cap-" + TestFixtures.randomSlugSuffix(), "Coopérative Parts");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Parts";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        fundCashBox(u, 10_000_000);
        return u;
    }

    /** Les natures de pièce du mois, pour dire si l'écriture est là. */
    private io.restassured.response.ValidatableResponse journal(UserEntity who) {
        LocalDate now = LocalDate.now();
        return givenAs(who).when()
                .get("/api/v1/accounting/journal?from=" + now.withDayOfMonth(1)
                        + "&to=" + now.withDayOfMonth(now.lengthOfMonth()) + "&perPage=100")
                .then().statusCode(200);
    }

    @Test
    void une_adhesion_validee_produit_la_piece_de_part_sociale() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "KOUAME", "firstName": "Awa", "gender": "FEMALE",
                          "status": "ACTIVE", "partsSocialesAmount": 25000,
                          "joinedAt": "%s", "preferredPaymentMethod": "especes" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/members").then().statusCode(201);

        journal(a).body("data.items.sourceType", hasItem("MEMBER_CAPITAL"));
    }

    @Test
    void sans_part_sociale_aucune_piece_n_est_fabriquee() {
        UserEntity a = admin();

        // Un membre qui n'a rien versé n'a pas d'écriture à produire :
        // en fabriquer une créditerait le capital de rien du tout.
        givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "SANSPART", "firstName": "Koffi", "gender": "MALE",
                          "status": "ACTIVE", "joinedAt": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/members").then().statusCode(201);

        journal(a).body("data.items.sourceType", not(hasItem("MEMBER_CAPITAL")));
    }

    @Test
    void le_reglage_coupe_vraiment_l_ecriture() {
        UserEntity a = admin();

        givenAs(a).contentType("application/json")
                .body("{ \"postMemberCapitalEntries\": false }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "COUPEE", "firstName": "Awa", "gender": "FEMALE",
                          "status": "ACTIVE", "partsSocialesAmount": 25000,
                          "joinedAt": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/members").then().statusCode(201);

        journal(a).body("data.items.sourceType", not(hasItem("MEMBER_CAPITAL")));
    }

    @Test
    void une_adhesion_en_attente_attend_sa_validation() {
        UserEntity a = admin();

        // Tant que le dossier n'est pas validé, rien n'est acquis : la
        // pièce naîtrait d'une adhésion qui peut encore être refusée.
        String id = givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "ATTENTE", "firstName": "Koffi", "gender": "MALE",
                          "status": "PENDING", "partsSocialesAmount": 25000,
                          "joinedAt": "%s" }
                        """.formatted(LocalDate.now()))
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
        journal(a).body("data.items.sourceType", not(hasItem("MEMBER_CAPITAL")));

        givenAs(a).contentType("application/json")
                .when().post("/api/v1/members/" + id + "/approve")
                .then().statusCode(200).body("data.status", equalTo("ACTIVE"));

        journal(a).body("data.items.sourceType", hasItem("MEMBER_CAPITAL"));
    }
}
