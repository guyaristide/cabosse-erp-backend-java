package com.ntech.cabosse.intake;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantActivity;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Retrouver les bordereaux d'un jour précis (demandé le 31/10/2026) : la
 * liste des réceptions se borne par dates, et une date mal écrite est
 * refusée plutôt qu'ignorée.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class IntakeNoteDateFilterTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-dt-" + TestFixtures.randomSlugSuffix(), "Coopérative Dates");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity activity = new TenantActivity();
        activity.code = "COMMODITY_TRADE";
        activity.label = "Négoce";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Dates";
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

    private void note(UserEntity admin, String siteId, String ref, LocalDate date) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "ref": "%s", "date": "%s", "siteId": "%s",
                          "productLabel": "CACAO", "bagCount": 1, "grossWeightKg": 40, "netWeightKg": 39 }
                        """.formatted(ref, date, siteId))
                .when().post("/api/v1/intake-notes").then().statusCode(201);
    }

    @Test
    void the_receptions_of_one_day_are_found_by_their_dates() {
        UserEntity admin = admin();
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String siteId = givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin central\",\"type\":\"CENTRAL_WAREHOUSE\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
        LocalDate day = LocalDate.of(2026, 10, 20);
        note(admin, siteId, "BR-1", day.minusDays(3));
        note(admin, siteId, "BR-2", day);
        note(admin, siteId, "BR-3", day.plusDays(2));

        givenAs(admin).when().get("/api/v1/intake-notes")
                .then().statusCode(200).body("data", hasSize(3));
        // Le même jour aux deux bornes : ce jour-là, et lui seul.
        givenAs(admin).when().get("/api/v1/intake-notes?from=" + day + "&to=" + day)
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].ref", equalTo("BR-2"));
        givenAs(admin).when().get("/api/v1/intake-notes?from=" + day)
                .then().statusCode(200).body("data", hasSize(2));
        givenAs(admin).when().get("/api/v1/intake-notes?to=" + day)
                .then().statusCode(200).body("data", hasSize(2));
        // Une date mal écrite est refusée, pas ignorée.
        givenAs(admin).when().get("/api/v1/intake-notes?from=20-10-2026")
                .then().statusCode(422);
    }
}
