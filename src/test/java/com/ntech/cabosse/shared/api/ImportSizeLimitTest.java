package com.ntech.cabosse.shared.api;

import com.ntech.cabosse.auth.service.PasswordHasher;
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
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;

/**
 * Un import a une taille au-delà de laquelle il n'est plus un import.
 *
 * <p>Les tableaux de lignes n'avaient aucune borne, et l'aperçu charge
 * tout le référentiel pour comparer chaque ligne à chaque candidat :
 * un seul envoi, rejouable à volonté et sans trace au journal puisque
 * l'aperçu n'écrit rien, occupait le serveur à proportion du carré de
 * sa taille (audit du 17/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ImportSizeLimitTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-size-" + TestFixtures.randomSlugSuffix(), "Coopérative Taille");
        // Le droit d'importer des sociétaires suit la capacité du modèle.
        tenant.organizationModel = com.ntech.cabosse.tenant.entity.TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Test";
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
    void an_import_beyond_the_bound_is_refused_before_being_read() {
        UserEntity a = admin();
        String tooMany = "[" + String.join(",", Collections.nCopies(10_001, "{}")) + "]";

        givenAs(a).contentType("application/json").body(tooMany)
                .when().post("/api/v1/sites/import/preview")
                .then().statusCode(400);
        givenAs(a).contentType("application/json").body(tooMany)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(400);
    }
}
