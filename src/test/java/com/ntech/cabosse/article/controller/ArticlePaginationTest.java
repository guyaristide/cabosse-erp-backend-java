package com.ntech.cabosse.article.controller;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Contrat de pagination des endpoints de liste (CLAUDE.md §10.3 / §10.4),
 * vérifié sur {@code GET /api/v1/articles} :
 *
 * <ol>
 *   <li>Réponse enveloppée {@code ApiResponse<Pagination<T>>} — jamais de
 *       {@code List} brute dans {@code data}.</li>
 *   <li>Découpage correct : {@code total}, {@code totalOfPages},
 *       {@code currentPage}, taille de page respectée.</li>
 *   <li>{@code perPage > 100} rejeté en {@code 400 Bad Request}.</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ArticlePaginationTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-page-" + TestFixtures.randomSlugSuffix(), "Coopérative Pagination");
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

    private void createArticle(UserEntity admin, String name) {
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "type": "RAW_MATERIAL", "name": "%s", "unit": "kg" }
                        """.formatted(name))
                .when().post("/api/v1/articles")
                .then().statusCode(201);
    }

    @Test
    void list_is_paginated_with_totals_and_page_slicing() {
        UserEntity admin = tenantAdmin();
        createArticle(admin, "Fèves de cacao");
        createArticle(admin, "Latex hévéa");
        createArticle(admin, "Soude caustique");

        // Page 0 : 2 éléments sur 3, métadonnées complètes
        givenAs(admin)
                .when().get("/api/v1/articles?perPage=2")
                .then().statusCode(200)
                .body("data.total", equalTo(3))
                .body("data.totalOfPages", equalTo(2))
                .body("data.perPage", equalTo(2))
                .body("data.currentPage", equalTo(0))
                .body("data.items", hasSize(2));

        // Page 1 : le reliquat
        givenAs(admin)
                .when().get("/api/v1/articles?perPage=2&page=1")
                .then().statusCode(200)
                .body("data.currentPage", equalTo(1))
                .body("data.items", hasSize(1));
    }

    @Test
    void filters_are_echoed_and_applied() {
        UserEntity admin = tenantAdmin();
        createArticle(admin, "Fèves de cacao");
        createArticle(admin, "Soude caustique");

        givenAs(admin)
                .when().get("/api/v1/articles?q=cacao")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.filters.q", equalTo("cacao"))
                .body("data.items[0].name", equalTo("Fèves de cacao"));
    }

    @Test
    void perPage_above_maximum_is_rejected_with_400() {
        UserEntity admin = tenantAdmin();
        givenAs(admin)
                .when().get("/api/v1/articles?perPage=101")
                .then().statusCode(400);
    }
}
