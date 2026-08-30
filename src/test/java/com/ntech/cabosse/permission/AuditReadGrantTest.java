package com.ntech.cabosse.permission;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.migrations.M081_GrantAuditReadToAdminProfiles;
import com.ntech.cabosse.migrations.M082_KeepAdministratorProfileComplete;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sortir le journal d'audit du menu ne doit priver personne.
 *
 * <p>Le journal n'était gardé que par le rôle d'administrateur : la
 * structure ne pouvait ni l'ouvrir à un contrôleur, ni le fermer à un
 * administrateur. Il porte désormais son propre droit. Encore faut-il que
 * les profils qui administraient déjà la structure ne perdent pas au
 * passage un accès qu'ils avaient — c'est ce que la migration garantit,
 * et c'est ce que ce test tient.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AuditReadGrantTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase() {
        return mongoClient.getDatabase(
                "tenant_test_audit_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static void seedRole(MongoDatabase db, String code, String... permissions) {
        db.getCollection("tenant_roles").insertOne(new Document("_id", UUID.randomUUID())
                .append("code", code)
                .append("permissions", List.of(permissions))
                .append("active", true));
    }

    @SuppressWarnings("unchecked")
    private static List<String> permissionsOf(MongoDatabase db, String code) {
        Document d = db.getCollection("tenant_roles").find(new Document("code", code)).first();
        return d == null ? List.of() : (List<String>) d.get("permissions");
    }

    @Test
    void the_profiles_that_administer_keep_their_access_to_the_log() {
        MongoDatabase db = freshDatabase();
        seedRole(db, "ADMINISTRATEUR", Permission.USER_MANAGE.name(), Permission.SETTINGS_READ.name());

        new M081_GrantAuditReadToAdminProfiles().execute(db);

        assertThat(permissionsOf(db, "ADMINISTRATEUR")).contains(Permission.AUDIT_READ.name());
    }

    @Test
    void the_other_profiles_do_not_get_it_by_surprise() {
        MongoDatabase db = freshDatabase();
        // Lire le paramétrage n'est pas administrer : la gouvernance et le
        // comptable n'ouvrent pas le journal tant que la structure ne le
        // leur a pas donné.
        seedRole(db, "GOUVERNANCE", Permission.SETTINGS_READ.name(), Permission.MEMBER_READ.name());
        seedRole(db, "COMPTABLE", Permission.ACCOUNTING_READ.name());

        new M081_GrantAuditReadToAdminProfiles().execute(db);

        assertThat(permissionsOf(db, "GOUVERNANCE")).doesNotContain(Permission.AUDIT_READ.name());
        assertThat(permissionsOf(db, "COMPTABLE")).doesNotContain(Permission.AUDIT_READ.name());
    }

    @Test
    void replaying_it_does_not_duplicate_the_right() {
        MongoDatabase db = freshDatabase();
        seedRole(db, "ADMINISTRATEUR", Permission.USER_MANAGE.name());

        var migration = new M081_GrantAuditReadToAdminProfiles();
        migration.execute(db);
        migration.execute(db);

        assertThat(permissionsOf(db, "ADMINISTRATEUR"))
                .filteredOn(Permission.AUDIT_READ.name()::equals)
                .hasSize(1);
    }

    @Test
    void a_profile_created_after_the_delivery_is_served_too() {
        MongoDatabase db = freshDatabase();
        var migration = new M081_GrantAuditReadToAdminProfiles();
        migration.execute(db);

        // Le rejeu couvre l'administrateur nommé entre deux démarrages,
        // sans quoi il perdrait un accès que ses pairs conservent.
        seedRole(db, "ADMIN_BIS", Permission.USER_MANAGE.name());
        migration.execute(db);

        assertThat(permissionsOf(db, "ADMIN_BIS")).contains(Permission.AUDIT_READ.name());
    }

    @Test
    void the_administrator_profile_gets_every_new_right_without_a_dedicated_migration() {
        // Le vrai correctif : le profil est semé avec tout le catalogue,
        // mais une seule fois. Un droit livré après l'ouverture de la
        // structure ne l'atteignait jamais, et il fallait une migration de
        // rattrapage par permission ajoutée.
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-adm-" + TestFixtures.randomSlugSuffix(), "Structure Administrateur");
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);
        seedRole(db, "ADMINISTRATEUR", Permission.SETTINGS_READ.name());

        new M082_KeepAdministratorProfileComplete().execute(db, mongoClient);

        List<String> held = permissionsOf(db, "ADMINISTRATEUR");
        assertThat(held).contains(Permission.AUDIT_READ.name(), Permission.USER_MANAGE.name());
        // Et il porte bien tout ce que ses capacités rendent applicable.
        assertThat(held).hasSizeGreaterThan(10);
    }

    @Test
    void a_profile_already_complete_is_left_alone() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-adm2-" + TestFixtures.randomSlugSuffix(), "Structure Administrateur 2");
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);
        seedRole(db, "ADMINISTRATEUR", Permission.SETTINGS_READ.name());

        var migration = new M082_KeepAdministratorProfileComplete();
        migration.execute(db, mongoClient);
        int after = permissionsOf(db, "ADMINISTRATEUR").size();
        migration.execute(db, mongoClient);

        assertThat(permissionsOf(db, "ADMINISTRATEUR")).hasSize(after);
    }
}
