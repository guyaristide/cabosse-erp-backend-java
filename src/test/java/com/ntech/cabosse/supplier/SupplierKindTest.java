package com.ntech.cabosse.supplier;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Ce qu'un fournisseur est, d'un coup d'œil.
 *
 * <p>Une liste de fournisseurs mêlait planteurs, délégués et garagistes
 * sans rien pour les distinguer (signalé le 21/10/2026). Chaque fiche
 * dit désormais ce qu'elle est : le miroir d'un sociétaire est un
 * producteur, un collecteur est un délégué même s'il est sociétaire, le
 * reste est un fournisseur. Et la liste se restreint à l'un des
 * trois.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class SupplierKindTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-kind-" + TestFixtures.randomSlugSuffix(), "Coopérative Genre");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Genre";
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

    @Test
    void each_supplier_says_what_it_is_and_the_list_filters_on_it() {
        UserEntity a = admin();
        givenAs(a).contentType("application/json")
                .body("{ \"name\": \"Garage Koné\" }")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .body("data.kind", equalTo("SUPPLIER"));
        givenAs(a).contentType("application/json")
                .body("{ \"name\": \"TRAORE Moussa\", \"collector\": true }")
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .body("data.kind", equalTo("DELEGATE"));
        givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "AKA", "firstName": "Adjoua", "gender": "FEMALE", "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members").then().statusCode(201);

        // Le miroir du sociétaire est un producteur, rattaché à sa fiche.
        givenAs(a).when().get("/api/v1/suppliers?q=AKA")
                .then().statusCode(200)
                .body("data.items[0].kind", equalTo("PRODUCER"))
                .body("data.items[0].memberId", notNullValue());

        givenAs(a).when().get("/api/v1/suppliers?kind=SUPPLIER&perPage=100")
                .then().statusCode(200)
                .body("data.items.name", hasItem("Garage Koné"))
                .body("data.items.kind", everyItem(equalTo("SUPPLIER")));
        givenAs(a).when().get("/api/v1/suppliers?kind=PRODUCER&perPage=100")
                .then().statusCode(200)
                .body("data.items.kind", everyItem(equalTo("PRODUCER")))
                .body("data.items.name", not(hasItem("Garage Koné")));
        givenAs(a).when().get("/api/v1/suppliers?kind=DELEGATE&perPage=100")
                .then().statusCode(200)
                .body("data.items.name", hasItem("TRAORE Moussa"))
                .body("data.items.kind", everyItem(equalTo("DELEGATE")));
    }
}
