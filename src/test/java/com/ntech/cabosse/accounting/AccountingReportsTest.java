package com.ntech.cabosse.accounting;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les rapports comptables à l'écran (grand livre, bilan, compte de
 * résultat) : les mêmes lignes que les exports fichiers, servies en
 * JSON à l'écran Rapports/États.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AccountingReportsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity admin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-rapports-" + TestFixtures.randomSlugSuffix(), "Coopérative Rapports");
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Rapports";
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
    void the_onscreen_reports_show_what_the_files_would_deliver() {
        UserEntity admin = admin();
        fundCashBox(admin, 2_000_000);

        // ─── Grand livre : le compte de caisse porte le mouvement ───
        List<Map<String, Object>> ledger = givenAs(admin)
                .when().get("/api/v1/accounting/reports/general-ledger?account=571000")
                .then().statusCode(200).extract().path("data");
        assertThat(ledger).isNotEmpty();
        Map<String, Object> last = ledger.get(ledger.size() - 1);
        assertThat(((Number) last.get("runningBalance")).longValue()).isEqualTo(2_000_000L);

        // Sans compte, la demande est refusée plutôt que devinée.
        givenAs(admin).when().get("/api/v1/accounting/reports/general-ledger")
                .then().statusCode(400);

        // ─── Bilan : actif et passif se répondent ───
        List<Map<String, Object>> bilan = givenAs(admin)
                .when().get("/api/v1/accounting/reports/balance-sheet")
                .then().statusCode(200).extract().path("data");
        long totalActif = totalOf(bilan, "TOTAL ACTIF");
        long totalPassif = totalOf(bilan, "TOTAL PASSIF");
        assertThat(totalActif).isEqualTo(2_000_000L);
        assertThat(totalPassif).isEqualTo(totalActif);

        // ─── Compte de résultat : structure présente même à vide ───
        List<Map<String, Object>> cr = givenAs(admin)
                .when().get("/api/v1/accounting/reports/income-statement")
                .then().statusCode(200).extract().path("data");
        assertThat(cr.stream().map(r -> r.get("rubrique")))
                .contains("TOTAL CHARGES", "TOTAL PRODUITS");
    }

    private static long totalOf(List<Map<String, Object>> rows, String rubrique) {
        return rows.stream()
                .filter(r -> rubrique.equals(r.get("rubrique")))
                .map(r -> ((Number) r.get("montant")).longValue())
                .findFirst().orElseThrow();
    }
}
