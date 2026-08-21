package com.ntech.cabosse.capability;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Activation d'un module après le provisioning.
 *
 * <p>La promesse du produit : un module s'active par tenant sans toucher
 * aux autres. Or les migrations conditionnées par capacité étaient jouées
 * une seule fois : marquées exécutées alors qu'elles n'avaient rien fait,
 * elles ne repassaient jamais. Un tenant qui activait la capacité plus
 * tard voyait le module apparaître à l'écran avec des collections sans
 * index ni semis, cassé pour lui seul, silencieusement.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class CapabilityActivationTest extends AbstractIntegrationTest {

    @Inject TenantMigrationRunner runner;
    @Inject TenantCapabilityService capabilityService;
    @Inject MongoClient client;

    private boolean hasIndex(MongoDatabase db, String collection, String indexName) {
        for (Document index : db.getCollection(collection).listIndexes()) {
            if (indexName.equals(index.getString("name"))) return true;
        }
        return false;
    }

    @Test
    void structures_appear_when_the_capability_is_activated_later() {
        // Une société privée sans production agricole : ni membres, ni crédits.
        TenantEntity tenant = fixtures.createActiveTenant(
                "ent-activ-" + TestFixtures.randomSlugSuffix(), "Société Négoce Général");
        tenant.organizationModel = TenantOrganizationModel.PRIVATE_COMPANY;
        tenant.activities = new ArrayList<>();
        tenants.update(tenant);

        runner.runMigrationsFor(tenant.databaseName);
        MongoDatabase db = client.getDatabase(tenant.databaseName);

        assertThat(hasIndex(db, "members", "uniq_members_code"))
                .as("sans la capacité, les structures du module n'existent pas")
                .isFalse();
        assertThat(hasIndex(db, "member_credits", "uniq_member_credits_ref")).isFalse();

        // La structure devient une coopérative : la capacité s'active.
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenants.update(tenant);
        runner.runMigrationsFor(tenant.databaseName);

        assertThat(hasIndex(db, "members", "uniq_members_code"))
                .as("l'activation rejoue les migrations conditionnelles")
                .isTrue();
        assertThat(hasIndex(db, "member_credits", "uniq_member_credits_ref")).isTrue();
        assertThat(hasIndex(db, "campaigns", "uniq_campaigns_code")).isTrue();

        // Rejouable : un troisième passage ne casse rien.
        runner.runMigrationsFor(tenant.databaseName);
        assertThat(hasIndex(db, "members", "uniq_members_code")).isTrue();
    }

    @Test
    void the_guard_and_the_service_derive_the_same_capabilities() {
        // Le garde des migrations duplique volontairement la dérivation du
        // service (contexte hors CDI). Cette parité était affirmée par un
        // commentaire et vérifiée par personne : la voici tenue par un test.
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-parite-" + TestFixtures.randomSlugSuffix(), "Coopérative Parité");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new ArrayList<>();
        TenantActivity cacao = new TenantActivity();
        cacao.code = "cacao-production";
        cacao.label = "Production de cacao";
        cacao.isPrimary = true;
        tenant.activities.add(cacao);
        tenants.update(tenant);

        Set<TenantCapability> fromService = new HashSet<>(capabilityService.capabilitiesOf(tenant));
        Set<TenantCapability> fromGuard =
                new HashSet<>(CapabilityMigrationGuard.capabilitiesFor(tenant.databaseName, client));
        assertThat(fromGuard).isEqualTo(fromService);

        // Et sur le cas nu, sans activité ni coopérative.
        TenantEntity plain = fixtures.createActiveTenant(
                "ent-parite-" + TestFixtures.randomSlugSuffix(), "Société Parité");
        plain.organizationModel = TenantOrganizationModel.PRIVATE_COMPANY;
        plain.activities = new ArrayList<>();
        tenants.update(plain);

        assertThat(new HashSet<>(CapabilityMigrationGuard.capabilitiesFor(plain.databaseName, client)))
                .isEqualTo(new HashSet<>(capabilityService.capabilitiesOf(plain)));
    }
}
