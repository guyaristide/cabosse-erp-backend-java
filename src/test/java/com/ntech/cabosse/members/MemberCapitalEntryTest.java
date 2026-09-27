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
 * L'écriture de part sociale, par tous les chemins qui créent un membre
 * (signalé le 27/09/2026).
 *
 * <p>L'import sautait la pièce, toujours, au motif qu'une base reprise
 * décrit des adhésions dont l'argent est encaissé depuis des années. Le
 * raisonnement vaut pour une reprise, pas pour tous les imports, et il
 * n'appartenait pas au logiciel de trancher à la place de la structure :
 * le réglage est là pour ça. Qui reprend un historique le coupe le temps
 * de l'import, qui enregistre des adhésions réelles par fichier le laisse
 * actif.</p>
 *
 * <p>Ces tests tiennent donc une seule règle, valable partout : la pièce
 * suit le réglage, la saisie et l'import compris. Une porte qui
 * déciderait pour son compte est un piège, quel que soit le sens dans
 * lequel elle tranche.</p>
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
    void l_import_produit_la_piece_quand_le_reglage_est_actif() {
        UserEntity a = admin();

        // L'import sautait l'écriture quoi qu'il arrive : le réglage ne
        // servait à rien de ce côté, alors qu'il existe pour décider.
        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "lastName": "IMPORTE", "firstName": "Awa",
                            "gender": "FEMALE", "partsSocialesAmount": "25000" } ]
                        """)
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200);

        journal(a).body("data.items.sourceType", hasItem("MEMBER_CAPITAL"));
    }

    @Test
    void l_import_d_un_historique_se_coupe_par_le_reglage() {
        UserEntity a = admin();

        // Le geste qui protège une reprise : couper le réglage le temps
        // du fichier, plutôt qu'une règle codée en dur que personne ne
        // voit et que personne ne peut lever.
        givenAs(a).contentType("application/json")
                .body("{ \"postMemberCapitalEntries\": false }")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        givenAs(a).contentType("application/json")
                .body("""
                        [ { "rowNumber": 1, "lastName": "REPRISE", "firstName": "Koffi",
                            "gender": "MALE", "partsSocialesAmount": "25000" } ]
                        """)
                .when().post("/api/v1/members/import/commit?includeWarnings=true")
                .then().statusCode(200);

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
