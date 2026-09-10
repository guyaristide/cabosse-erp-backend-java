package com.ntech.cabosse.catalog;

import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Le catalogue des offres du back-office (constaté le 10/09/2026 en
 * production) : le fichier de seed portait d'anciens noms de champs
 * après le dé-marquage FCFA, les plans arrivaient sans prix et le tri
 * du catalogue tombait en 500. Les prix doivent être semés, et un plan
 * sans prix ne doit de toute façon plus faire tomber la liste.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PlanCatalogTest extends AbstractIntegrationTest {

    @Test
    void the_plan_catalog_answers_with_priced_plans() {
        var admin = fixtures.createPlatformAdmin(
                "plans.catalog@neiba-technologies.com", "Plans", "Catalog");
        givenAs(admin).when().get("/api/v1/admin/catalog/plans")
                .then().statusCode(200)
                .body("data.size()", greaterThan(0))
                .body("data.monthlyPrice", everyItem(notNullValue()));
    }
}
