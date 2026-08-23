package com.ntech.cabosse.suppliercategory;

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
import com.ntech.cabosse.shared.audit.AuditRepository;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Conditions de reprise par catégorie de fournisseur.
 *
 * <p>La coopérative ne reprend pas dans les mêmes conditions selon qui
 * apporte. Ce que le paramétrage doit permettre : poser la règle une fois
 * par catégorie, la surcharger pour le cas particulier, et retrouver en
 * fin de campagne ce que chaque canal a apporté et coûté.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupplierCategoryTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject AuditRepository auditEvents;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cat-" + TestFixtures.randomSlugSuffix(), "Coopérative Catégories");
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

        // Réglage tenant : 10 FCFA par kilo, la règle de repli.
        givenAs(u).contentType("application/json")
                .body("{\"delegateMarginMode\":\"PER_KG\",\"delegateMarginRate\":10}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);
        return u;
    }

    private String createCategory(UserEntity admin, String code, String name,
                                  String mode, String rate) {
        String margin = mode == null ? "" :
                ", \"marginMode\": \"%s\", \"marginRate\": %s".formatted(mode, rate);
        return givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"%s\", \"name\": \"%s\"%s }".formatted(code, name, margin))
                .when().post("/api/v1/supplier-categories").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name, String categoryId, String ownRate) {
        String code = "del-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String own = ownRate == null ? "" : ", \"collectorMarginRate\": " + ownRate;
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "%s", "collector": true,
                          "categoryId": "%s"%s }
                        """.formatted(code, name, categoryId, own))
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String receipt(UserEntity admin, String memberId, String articleId, String siteId,
                           int weightKg, String delegateId) {
        String delegatePart = delegateId == null ? ""
                : ", \"delegateSupplierId\": \"" + delegateId + "\"";
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "memberId": "%s", "articleId": "%s", "siteId": "%s",
                          "weightKg": %d, "guaranteedPricePerKgFcfa": 1000,
                          "paymentMethod": "CASH"%s }
                        """.formatted(LocalDate.now(), memberId, articleId, siteId,
                        weightKg, delegatePart))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .when().post("/api/v1/producer-purchases").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void the_category_sets_the_terms_and_the_supplier_overrides_them() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        // La catégorie des délégués paie 25 par kilo, au dessus du tenant.
        String delegates = createCategory(admin, "DELEGUE", "Délégué collecteur", "PER_KG", "25");

        String ordinary = createDelegate(admin, "Délégué Bangolo", delegates, null);
        String senior = createDelegate(admin, "Délégué chevronné", delegates, "40");

        String p1 = createProducer(admin, "Kone");
        String p2 = createProducer(admin, "Yao");

        // 100 kg par le délégué ordinaire : 100 × 25.
        String r1 = receipt(admin, p1, articleId, siteId, 100, ordinary);
        givenAs(admin).when().get("/api/v1/producer-purchases/" + r1)
                .then().statusCode(200)
                .body("data.delegateMarginFcfa", equalTo(2500F))
                .body("data.supplierCategoryName", equalTo("Délégué collecteur"));

        // Le taux propre au délégué chevronné prime sur celui de sa catégorie.
        String r2 = receipt(admin, p2, articleId, siteId, 100, senior);
        givenAs(admin).when().get("/api/v1/producer-purchases/" + r2)
                .then().statusCode(200)
                .body("data.delegateMarginFcfa", equalTo(4000F));
    }

    @Test
    void a_category_without_terms_falls_back_to_the_tenant() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        // Catégorie de pur classement : aucune condition propre.
        String plain = createCategory(admin, "PISTEUR", "Pisteur", null, null);
        String delegateId = createDelegate(admin, "Pisteur Duékoué", plain, null);
        String memberId = createProducer(admin, "Traoré");

        String r = receipt(admin, memberId, articleId, siteId, 200, delegateId);
        // 200 × 10, le taux du tenant.
        givenAs(admin).when().get("/api/v1/producer-purchases/" + r)
                .then().statusCode(200)
                .body("data.delegateMarginFcfa", equalTo(2000F))
                .body("data.supplierCategoryName", equalTo("Pisteur"));
    }

    @Test
    void the_end_of_season_report_separates_the_channels() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        String articleId = createArticle(admin);

        String delegates = createCategory(admin, "DELEGUE", "Délégué collecteur", "PER_KG", "25");
        String delegateId = createDelegate(admin, "Délégué Man", delegates, null);

        String viaDelegate = createProducer(admin, "Sangare");
        receipt(admin, viaDelegate, articleId, siteId, 1000, delegateId);
        receipt(admin, viaDelegate, articleId, siteId, 500, delegateId);

        // Apport direct : aucun intermédiaire à rémunérer.
        String direct = createProducer(admin, "Bamba");
        receipt(admin, direct, articleId, siteId, 300, null);

        givenAs(admin).when().get("/api/v1/supplier-categories/report")
                .then().statusCode(200)
                .body("data.totalWeightKg", equalTo(1800))
                .body("data.totalMarginFcfa", equalTo(37500.00F))
                .body("data.lines", hasSize(2))
                // Le canal délégué porte le plus gros volume : il vient en tête.
                .body("data.lines[0].categoryName", equalTo("Délégué collecteur"))
                .body("data.lines[0].supplierCount", equalTo(1))
                .body("data.lines[0].receiptCount", equalTo(2))
                .body("data.lines[0].weightKg", equalTo(1500))
                .body("data.lines[0].marginFcfa", equalTo(37500.00F))
                .body("data.lines[0].marginPerKgFcfa", equalTo(25.00F))
                // Les apports sans catégorie ne disparaissent pas de l'état.
                .body("data.lines[1].categoryId", equalTo(null))
                .body("data.lines[1].weightKg", equalTo(300))
                .body("data.lines[1].marginFcfa", equalTo(0));
    }

    @Test
    void a_rate_change_keeps_the_value_it_replaced() {
        UserEntity admin = tenantAdmin();
        String id = createCategory(admin, "DELEGUE", "Délégué collecteur", "PER_KG", "25");

        givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "DELEGUE", "name": "Délégué collecteur",
                          "marginMode": "PER_KG", "marginRate": 30 }
                        """)
                .when().put("/api/v1/supplier-categories/" + id)
                .then().statusCode(200)
                .body("data.marginRate", equalTo(30));

        // L'ancienne valeur reste lisible : « depuis quand ce taux ? » doit
        // trouver une réponse ailleurs que dans une mémoire.
        List<String> descriptions = auditEvents.search(
                        new AuditRepository.AuditQuery(null, "CATALOG_UPDATED", null, null, null, null),
                        0, 50)
                .stream().map(e -> e.description).toList();
        assertThat(descriptions, hasItem(containsString("25 par kg vers 30 par kg")));
    }

    @Test
    void a_duplicate_category_code_is_refused() {
        UserEntity admin = tenantAdmin();
        createCategory(admin, "DELEGUE", "Délégué collecteur", "PER_KG", "25");

        givenAs(admin).contentType("application/json")
                .body("{ \"code\": \"DELEGUE\", \"name\": \"Autre libellé\" }")
                .when().post("/api/v1/supplier-categories")
                .then().statusCode(409);
    }
}
