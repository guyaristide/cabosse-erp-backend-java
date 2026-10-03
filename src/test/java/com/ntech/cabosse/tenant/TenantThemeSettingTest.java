package com.ntech.cabosse.tenant;

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
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;

/**
 * Le thème et la densité appartiennent à la structure.
 *
 * <p>Une apparence par compte rendrait toute capture d'écran
 * incomparable d'un poste à l'autre, et le support ne saurait plus ce que
 * son interlocuteur a devant les yeux. La structure décide une fois pour
 * tous ses comptes (demandé le 03/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantThemeSettingTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-theme-" + TestFixtures.randomSlugSuffix(), "Coopérative Thème");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Thème";
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

    private io.restassured.response.ValidatableResponse setPrefs(UserEntity who, String body) {
        return givenAs(who).contentType("application/json").body(body)
                .when().put("/api/v1/me/tenant/preferences").then();
    }

    @Test
    void une_structure_qui_n_a_rien_choisi_reste_en_clair() {
        UserEntity a = admin();

        // Le thème ne bascule pas tout seul : une structure qui ouvre son
        // compte retrouve l'apparence qu'elle connaît.
        givenAs(a).when().get("/api/v1/me/tenant/preferences")
                .then().statusCode(200)
                .body("data.themeMode", equalTo("LIGHT"))
                .body("data.uiDensity", equalTo("COMFORTABLE"));
    }

    @Test
    void la_structure_passe_au_sombre_pour_tous_ses_comptes() {
        UserEntity a = admin();

        setPrefs(a, "{ \"themeMode\": \"DARK\", \"uiDensity\": \"COMPACT\" }")
                .statusCode(200)
                .body("data.themeMode", equalTo("DARK"))
                .body("data.uiDensity", equalTo("COMPACT"));

        givenAs(a).when().get("/api/v1/me/tenant/preferences")
                .then().statusCode(200).body("data.themeMode", equalTo("DARK"));
    }

    @Test
    void le_theme_de_l_appareil_est_un_choix_de_la_structure() {
        UserEntity a = admin();

        // Déléguer à l'appareil reste une décision prise une fois, pas
        // l'absence de décision.
        setPrefs(a, "{ \"themeMode\": \"SYSTEM\" }")
                .statusCode(200).body("data.themeMode", equalTo("SYSTEM"));
    }

    @Test
    void un_theme_inconnu_est_refuse() {
        UserEntity a = admin();

        setPrefs(a, "{ \"themeMode\": \"NEON\" }").statusCode(400);

        // Et rien n'a bougé : un réglage refusé ne laisse pas la structure
        // dans un état qu'elle n'a pas demandé.
        givenAs(a).when().get("/api/v1/me/tenant/preferences")
                .then().statusCode(200).body("data.themeMode", equalTo("LIGHT"));
    }
}
