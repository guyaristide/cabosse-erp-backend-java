package com.ntech.cabosse.shared.api;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrat commun à toutes les listes paginées.
 *
 * <p>Les seize endpoints paginés ont été écrits séparément et rendent la
 * même enveloppe. Rien ne garantissait qu'ils la remplissent de la même
 * façon : un {@code perPage} ignoré, un {@code totalOfPages} à zéro ou une
 * page suivante hors bornes ne se voient pas sur un jeu de données vide,
 * et cassent la navigation du front dès que les données arrivent. Ce test
 * vérifie le contrat sur tous à la fois, y compris aux bornes.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PaginationContractTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    private UserEntity admin;

    /**
     * Listes paginées atteignables sans construire de dossier métier
     * préalable. Une liste vide vérifie le contrat aussi bien qu'une liste
     * pleine : c'est l'enveloppe qui est sous test, pas son contenu.
     */
    static Stream<String> paginatedEndpoints() {
        return Stream.of(
                "/api/v1/articles",
                "/api/v1/suppliers",
                "/api/v1/customers",
                "/api/v1/members",
                "/api/v1/purchase-orders",
                "/api/v1/sales",
                "/api/v1/direct-receipts",
                "/api/v1/production-orders",
                "/api/v1/purchase-requests",
                "/api/v1/direct-expenses",
                "/api/v1/producer-purchases",
                "/api/v1/producer-payments",
                "/api/v1/member-credits",
                "/api/v1/collector-advances",
                "/api/v1/accounting/journal"
        );
    }

    @BeforeEach
    void setUp() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-page-" + TestFixtures.randomSlugSuffix(), "Coopérative Pagination");
        // Modèle coopérative : ouvre les listes gardées par une capacité
        // (membres, crédits, achats producteurs), qui sont justement celles
        // dont la pagination sert le plus sur le terrain.
        tenant.organizationModel = com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
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
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paginatedEndpoints")
    void l_enveloppe_paginee_est_coherente(String path) {
        JsonPath body = givenAs(admin)
                .when().get(withPaging(path, 0, 5))
                .then().statusCode(200)
                .extract().jsonPath();

        assertNotNull(body.get("data"), path + " : enveloppe absente");
        long total = body.getLong("data.total");
        int perPage = body.getInt("data.perPage");
        int currentPage = body.getInt("data.currentPage");
        int totalOfPages = body.getInt("data.totalOfPages");
        int nextPage = body.getInt("data.nextPage");
        int previousPage = body.getInt("data.previousPage");
        List<?> items = body.getList("data.items");

        assertTrue(total >= 0, path + " : total négatif");
        assertEquals(5, perPage, path + " : le perPage demandé n'est pas respecté");
        assertEquals(0, currentPage, path + " : la page demandée n'est pas respectée");
        assertTrue(totalOfPages >= 1,
                path + " : totalOfPages doit valoir au moins 1, même à vide"
                        + " (à zéro, le front n'affiche aucune page)");
        assertNotNull(items, path + " : items absent");
        assertTrue(items.size() <= perPage, path + " : la page déborde le perPage demandé");
        assertTrue(items.size() <= total, path + " : plus d'items que d'éléments annoncés");

        // Bornes de navigation : elles servent directement à construire les
        // liens précédent et suivant, une valeur hors bornes est une page morte.
        assertTrue(previousPage >= 0, path + " : previousPage négatif");
        assertTrue(nextPage >= 0 && nextPage <= totalOfPages - 1,
                path + " : nextPage hors bornes (" + nextPage + " pour " + totalOfPages + " pages)");
        assertEquals(0, previousPage, path + " : depuis la première page, previousPage doit rester 0");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paginatedEndpoints")
    void une_page_au_dela_du_dernier_element_reste_servie(String path) {
        // Un utilisateur qui garde une page en signet, ou qui supprime les
        // derniers éléments, demande une page qui n'existe plus. Cela doit
        // rendre une page vide, pas une erreur.
        JsonPath body = givenAs(admin)
                .when().get(withPaging(path, 999, 20))
                .then().statusCode(200)
                .extract().jsonPath();
        assertTrue(body.getList("data.items").isEmpty(),
                path + " : une page hors plage doit être vide");
        assertEquals(999, body.getInt("data.currentPage"),
                path + " : la page demandée doit être rendue telle quelle");
    }

    @Test
    void un_perPage_au_dela_du_plafond_est_refuse_explicitement() {
        // Refus franc plutôt que silencieux : sans cela, un client qui
        // demande 10 000 lignes croit les avoir toutes reçues.
        givenAs(admin)
                .when().get(withPaging("/api/v1/articles", 0, PageRequest.MAX_PER_PAGE + 1))
                .then().statusCode(400);
    }

    @Test
    void un_perPage_nul_ou_negatif_retombe_sur_le_defaut() {
        for (int perPage : new int[] { 0, -5 }) {
            givenAs(admin)
                    .when().get(withPaging("/api/v1/articles", 0, perPage))
                    .then().statusCode(200)
                    .body("data.perPage", org.hamcrest.Matchers.equalTo(PageRequest.DEFAULT_PER_PAGE));
        }
    }

    @Test
    void une_page_negative_est_ramenee_a_la_premiere() {
        givenAs(admin)
                .when().get(withPaging("/api/v1/articles", -3, 10))
                .then().statusCode(200)
                .body("data.currentPage", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void la_pagination_ne_perd_ni_ne_duplique_d_element() {
        // Trois articles, deux pages de deux : la réunion des pages doit
        // rendre exactement les trois, sans doublon. C'est le défaut
        // classique d'un tri instable au niveau de la base.
        for (String name : List.of("Cacao marchand", "Beurre de karité", "Fèves séchées")) {
            givenAs(admin).contentType("application/json")
                    .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"" + name + "\",\"unit\":\"kg\"}")
                    .when().post("/api/v1/articles").then().statusCode(201);
        }

        List<String> collected = new java.util.ArrayList<>();
        int page = 0;
        int totalOfPages;
        do {
            JsonPath body = givenAs(admin)
                    .when().get(withPaging("/api/v1/articles", page, 2))
                    .then().statusCode(200)
                    .extract().jsonPath();
            collected.addAll(body.getList("data.items.id", String.class));
            totalOfPages = body.getInt("data.totalOfPages");
            assertEquals(3, body.getLong("data.total"), "Le total doit rester constant d'une page à l'autre");
            page++;
        } while (page < totalOfPages);

        assertEquals(3, collected.size(), "Le parcours des pages doit rendre tous les éléments");
        assertEquals(3, new java.util.HashSet<>(collected).size(),
                "Aucun élément ne doit apparaître sur deux pages");
    }

    private static String withPaging(String path, int page, int perPage) {
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + "page=" + page + "&perPage=" + perPage;
    }
}
