package com.ntech.cabosse.members;

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
import java.time.Year;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Le code d'un producteur créé au passage.
 *
 * <p>Le compteur ne connaît que ce qu'il a lui-même distribué. Un code
 * apporté par un import ou saisi à la main lui reste invisible : il le
 * redistribuait ensuite, et la création était refusée en doublon. Neuf
 * producteurs à ouvrir au passage d'une comptabilisation échouaient tous
 * les neuf, et le bordereau entier restait à comptabiliser (constaté en
 * production le 06/10/2026).</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberCodeSequenceTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private static final int YEAR = Year.now().getValue();

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-code-" + TestFixtures.randomSlugSuffix(), "Coopérative Codes");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Codes";
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

    /** Un producteur dont le code est imposé, comme le ferait un import. */
    private void withCode(UserEntity a, String code, String lastName) {
        givenAs(a).contentType("application/json")
                .body("""
                        { "code": "%s", "lastName": "%s", "civilStatus": "UNKNOWN",
                          "status": "ACTIVE" }
                        """.formatted(code, lastName))
                .when().post("/api/v1/members").then().statusCode(201);
    }

    /** Un producteur sans code : la séquence le fournit. */
    private String generated(UserEntity a, String lastName) {
        return givenAs(a).contentType("application/json")
                .body("""
                        { "lastName": "%s", "civilStatus": "UNKNOWN", "status": "ACTIVE" }
                        """.formatted(lastName))
                .when().post("/api/v1/members")
                .then().statusCode(201).extract().path("data.code");
    }

    @Test
    void la_sequence_saute_les_codes_deja_pris() {
        UserEntity a = admin();
        // Un import a posé les premiers codes de la série : le compteur,
        // lui, est resté à zéro.
        withCode(a, "MB-%d-0001".formatted(YEAR), "SORO");
        withCode(a, "MB-%d-0002".formatted(YEAR), "FOFANA");

        String code = generated(a, "KONE");
        org.assertj.core.api.Assertions.assertThat(code)
                .isNotEqualTo("MB-%d-0001".formatted(YEAR))
                .isNotEqualTo("MB-%d-0002".formatted(YEAR))
                .startsWith("MB-%d-".formatted(YEAR));
    }

    @Test
    void plusieurs_creations_d_affilee_passent_toutes() {
        UserEntity a = admin();
        // Le cas réel : une comptabilisation ouvre neuf fiches de suite.
        for (int i = 1; i <= 9; i++) {
            withCode(a, "MB-%d-%04d".formatted(YEAR, i), "IMPORTE" + i);
        }

        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= 9; i++) {
            codes.add(generated(a, "NOUVEAU" + i));
        }

        // Neuf codes, tous distincts, et aucun de ceux que l'import avait
        // posés.
        org.assertj.core.api.Assertions.assertThat(codes).hasSize(9);
        for (int i = 1; i <= 9; i++) {
            org.assertj.core.api.Assertions.assertThat(codes)
                    .doesNotContain("MB-%d-%04d".formatted(YEAR, i));
        }
    }

    @Test
    void un_code_saisi_en_double_reste_refuse() {
        UserEntity a = admin();
        withCode(a, "MB-%d-0001".formatted(YEAR), "SORO");

        // La séquence saute ce qui est pris ; un code imposé à la main,
        // lui, doit toujours se heurter au doublon.
        givenAs(a).contentType("application/json")
                .body("""
                        { "code": "MB-%d-0001", "lastName": "AUTRE",
                          "civilStatus": "UNKNOWN", "status": "ACTIVE" }
                        """.formatted(YEAR))
                .when().post("/api/v1/members")
                .then().statusCode(422)
                .body("statusCode", not(equalTo(201)));
    }
}
