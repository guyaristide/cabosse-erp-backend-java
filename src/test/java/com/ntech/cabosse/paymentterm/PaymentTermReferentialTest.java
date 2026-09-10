package com.ntech.cabosse.paymentterm;

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
import static org.hamcrest.Matchers.hasSize;

/**
 * Référentiel des conditions de paiement : remplace la saisie libre des
 * fiches fournisseur et des bons de commande. Code dérivé du libellé,
 * doublon refusé sans casse, désactivation réversible.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PaymentTermReferentialTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-pyt-" + TestFixtures.randomSlugSuffix(), "Coopérative Conditions");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Conditions";
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
    void the_referential_builds_itself_and_refuses_duplicates() {
        UserEntity admin = admin();

        String id = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"30 jours fin de mois\"}")
                .when().post("/api/v1/payment-terms").then().statusCode(201)
                .body("data.code", equalTo("30-jours-fin-de-mois"))
                .extract().path("data.id");

        // Le même code, quelle que soit la casse, est un doublon.
        givenAs(admin).contentType("application/json")
                .body("{\"code\":\"30-JOURS-FIN-DE-MOIS\",\"name\":\"30 j FDM\"}")
                .when().post("/api/v1/payment-terms").then().statusCode(409);

        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Comptant\"}")
                .when().post("/api/v1/payment-terms").then().statusCode(201);

        givenAs(admin).when().get("/api/v1/payment-terms")
                .then().statusCode(200).body("data", hasSize(2));

        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"30 jours fin de mois, 2 % d'escompte\"}")
                .when().put("/api/v1/payment-terms/" + id).then().statusCode(200)
                .body("data.name", equalTo("30 jours fin de mois, 2 % d'escompte"));

        givenAs(admin).when().patch("/api/v1/payment-terms/" + id + "/active?value=false")
                .then().statusCode(200).body("data.active", equalTo(false));
    }
}
