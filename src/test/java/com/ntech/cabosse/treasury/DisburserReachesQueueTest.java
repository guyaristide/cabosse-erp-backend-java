package com.ntech.cabosse.treasury;

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

/**
 * Qui décaisse atteint la file où l'on décaisse (relevé le 30/09/2026).
 *
 * <p>Le profil d'une caissière en production portait trois droits de
 * décaissement — avance approuvée, crédit approuvé, règlement des
 * livraisons — et le solde de sa caisse. L'entrée Trésorerie ne lui
 * apparaissait pas, et la file des décaissements lui était fermée : elle
 * avait le geste, pas le chemin pour le faire.</p>
 *
 * <p>Même forme que l'approbation d'une demande d'achat et
 * l'encaissement d'une vente, corrigés avant elle : un droit d'agir
 * gardé derrière un droit de lire. Un consultant qui relit le profil n'y
 * voit rien d'anormal, puisque les droits sont bien là.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DisburserReachesQueueTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity user(String prefix, String role) {
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = prefix + "-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = prefix;
        u.lastName = "File";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(role);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-file-" + TestFixtures.randomSlugSuffix(), "Coopérative File");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        return user("admin", Roles.TENANT_ADMIN);
    }

    private String createRole(UserEntity admin, String name, String... permissions) {
        String perms = String.join(", ",
                java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return givenAs(admin).contentType("application/json")
                .body("{ \"name\": \"%s\", \"permissions\": [%s] }".formatted(name, perms))
                .when().post("/api/v1/tenant-roles").then().statusCode(201)
                .extract().path("data.id");
    }

    private void assign(UserEntity admin, UserEntity target, String roleId) {
        givenAs(admin).contentType("application/json")
                .body("{ \"roleIds\": [\"%s\"] }".formatted(roleId))
                .when().put("/api/v1/tenant-roles/users/" + target.id)
                .then().statusCode(204);
    }

    @Test
    void la_caissiere_atteint_la_file_des_decaissements() {
        UserEntity a = admin();
        UserEntity cashier = user("caissiere", Roles.USER);

        // Le profil réel d'une caissière : elle décaisse, elle ne lit pas
        // la comptabilité et ne tient pas les transferts de fonds.
        String role = createRole(a, "Caissière " + TestFixtures.randomSlugSuffix(),
                "COLLECTION_ADVANCE_DISBURSE", "MEMBER_CREDIT_DISBURSE",
                "COLLECTION_PAYMENT_WRITE", "TREASURY_CASH_BALANCE");
        assign(a, cashier, role);

        givenAs(cashier).when().get("/api/v1/treasury/payables").then().statusCode(200);
    }

    @Test
    void un_droit_de_lecture_sans_rapport_n_ouvre_pas_la_file() {
        UserEntity a = admin();
        UserEntity other = user("magasinier", Roles.USER);

        // La file reste fermée à qui n'a rien à y faire : l'ouvrir à qui
        // décaisse ne doit pas l'ouvrir à tout le monde.
        String role = createRole(a, "Magasinier " + TestFixtures.randomSlugSuffix(),
                "STOCK_READ", "MEMBER_READ");
        assign(a, other, role);

        givenAs(other).when().get("/api/v1/treasury/payables").then().statusCode(403);
    }
}
