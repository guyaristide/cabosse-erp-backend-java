package com.ntech.cabosse.shared.export;

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

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sélection des colonnes d'un export (paramètre {@code columns}) et
 * découverte du catalogue de colonnes ({@code format=meta}).
 *
 * <p>Le contrat protège le sélecteur du front : les libellés renvoyés
 * par {@code meta} sont exactement ceux que {@code columns} accepte,
 * et une sélection qui ne matche rien rend l'export complet plutôt
 * que vide.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ExportColumnSelectionTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-cols-" + TestFixtures.randomSlugSuffix(), "Coopérative Colonnes");
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
        return u;
    }

    @Test
    void meta_lists_the_columns_and_columns_param_restricts_the_export() {
        UserEntity admin = tenantAdmin();
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Delta Négoce\"}")
                .when().post("/api/v1/suppliers").then().statusCode(201);

        // La découverte donne les libellés exacts, sans donnée métier.
        givenAs(admin).when().get("/api/v1/suppliers/export?format=meta")
                .then().statusCode(200)
                .body("columns", hasItems("Code", "Nom"));

        // Une sélection restreint l'export à ces colonnes, dans l'ordre.
        String csv = givenAs(admin)
                .when().get("/api/v1/suppliers/export?format=csv&columns=Nom&columns=Code")
                .then().statusCode(200)
                .extract().asString();
        String header = csv.lines().findFirst().orElse("");
        assertTrue(header.contains("Code") && header.contains("Nom"), header);
        assertFalse(header.contains("Raison sociale"), header);
        assertTrue(csv.contains("Delta Négoce"));

        // Une sélection qui ne matche rien rend l'export complet.
        String full = givenAs(admin)
                .when().get("/api/v1/suppliers/export?format=csv&columns=Inexistante")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(full.lines().findFirst().orElse("").contains("Raison sociale"));
    }
}
