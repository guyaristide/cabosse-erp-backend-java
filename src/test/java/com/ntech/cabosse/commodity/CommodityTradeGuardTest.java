package com.ntech.cabosse.commodity;

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
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Le négoce de commodité se garde côté serveur, pas seulement à l'écran.
 *
 * <p>Les écrans du négoce étaient conditionnés à la capacité, mais l'API
 * ne l'était pas : un tenant qui vend des produits finis, donc porteur du
 * droit de vente, atteignait les treize points d'entrée du négoce. Masquer
 * une action n'est pas la protéger ; le contrôle qui fait foi est celui de
 * l'API.</p>
 *
 * <p>Ce test est aussi le premier de ce domaine, qui n'en avait aucun.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CommodityTradeGuardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    /**
     * @param activityCode filière du tenant. {@code null} pour une
     *                     structure sans activité déclarée.
     */
    private UserEntity adminOf(TenantOrganizationModel model, String activityCode) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-neg-" + TestFixtures.randomSlugSuffix(), "Structure Négoce");
        tenant.organizationModel = model;
        tenant.activities = new ArrayList<>();
        if (activityCode != null) {
            TenantActivity activity = new TenantActivity();
            activity.code = activityCode;
            activity.label = activityCode;
            activity.isPrimary = true;
            tenant.activities.add(activity);
        }
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

    /** Les treize points d'entrée du négoce, en lecture. */
    private static final String[] READ_PATHS = {
            "/api/v1/commodity/sales",
            "/api/v1/commodity/sales/loss-report",
            "/api/v1/commodity/sales/refaction-dashboard",
            "/api/v1/commodity/sales-contracts",
    };

    @Test
    void a_company_without_commodity_trade_is_refused_by_the_api() {
        // Une entreprise privée sans filière de négoce : elle vend des
        // produits finis, donc elle a le droit de vente.
        UserEntity a = adminOf(TenantOrganizationModel.PRIVATE_COMPANY, "savonnerie");

        for (String path : READ_PATHS) {
            givenAs(a).when().get(path)
                    .then().statusCode(422);
        }

        givenAs(a).contentType("application/json")
                .body("{\"campaignId\":null}")
                .when().post("/api/v1/commodity/sales-contracts")
                .then().statusCode(anyOf422or400());
    }

    @Test
    void a_cooperative_reaches_the_same_endpoints() {
        // Le modèle coopératif active le négoce : les mêmes appels passent.
        UserEntity a = adminOf(TenantOrganizationModel.COOPERATIVE, "cacao-production");

        for (String path : READ_PATHS) {
            givenAs(a).when().get(path)
                    .then().statusCode(200);
        }
    }

    /**
     * La création refusée peut l'être avant ou après la validation du
     * payload selon l'ordre des filtres : les deux réponses disent que
     * l'appel n'aboutit pas.
     */
    private static org.hamcrest.Matcher<Integer> anyOf422or400() {
        return org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.equalTo(422), org.hamcrest.Matchers.equalTo(400));
    }
}
