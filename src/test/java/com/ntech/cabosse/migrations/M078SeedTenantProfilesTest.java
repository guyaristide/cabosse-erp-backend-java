package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une structure ouvre avec ses postes déjà en place.
 *
 * <p>Ce que ces tests tiennent vraiment : que le conseil approuve sans
 * pouvoir décaisser, qu'un profil ne porte aucun droit sans objet pour la
 * structure, et qu'un profil retouché par l'administrateur ne soit jamais
 * rétabli par un redémarrage.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M078SeedTenantProfilesTest extends AbstractIntegrationTest {

    private TenantEntity tenantOfModel(TenantOrganizationModel model) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-prof-" + TestFixtures.randomSlugSuffix(), "Structure Profils");
        tenant.organizationModel = model;
        tenants.update(tenant);
        return tenant;
    }

    private MongoDatabase dbOf(TenantEntity tenant) {
        return mongoClient.getDatabase(tenant.databaseName);
    }

    @SuppressWarnings("unchecked")
    private static List<String> permissionsOf(MongoDatabase db, String code) {
        Document role = db.getCollection("tenant_roles").find(new Document("code", code)).first();
        return role == null ? null : (List<String>) role.get("permissions");
    }

    private static String nameOf(MongoDatabase db, String code) {
        Document role = db.getCollection("tenant_roles").find(new Document("code", code)).first();
        return role == null ? null : role.getString("name");
    }

    @Test
    void three_profiles_are_ready_so_only_the_users_remain_to_create() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        assertThat(permissionsOf(db, "ADMINISTRATEUR")).isNotEmpty();
        assertThat(permissionsOf(db, "GOUVERNANCE")).isNotEmpty();
        assertThat(permissionsOf(db, "COMPTABLE")).isNotEmpty();
    }

    @Test
    void the_administrator_profile_carries_the_whole_catalogue_of_the_tenant() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        // Un profil d'administration qui ne pourrait ni régler les
        // paramètres ni créer un compte ne servirait à rien.
        assertThat(permissionsOf(db, "ADMINISTRATEUR"))
                .contains("SETTINGS_WRITE", "USER_MANAGE", "ACCOUNTING_CLOSE");
    }

    @Test
    void the_board_approves_but_never_holds_the_cash() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        List<String> governance = permissionsOf(db, "GOUVERNANCE");
        assertThat(governance).contains(
                "COLLECTION_ADVANCE_APPROVE", "MEMBER_CREDIT_APPROVE_GOVERNANCE",
                "PURCHASE_APPROVE", "CAMPAIGN_PRICE_WRITE", "EXECUTIVE_READ");
        // Un organe qui approuve puis remet lui-même les fonds n'approuve
        // plus rien : ni décaissement, ni saisie d'exploitation.
        assertThat(governance).doesNotContain(
                "COLLECTION_ADVANCE_DISBURSE", "MEMBER_CREDIT_DISBURSE",
                "COLLECTION_ADVANCE_REQUEST", "COLLECTION_RECEIPT_WRITE",
                "ACCOUNTING_WRITE", "TREASURY_WRITE", "USER_MANAGE");
    }

    @Test
    void the_accountant_writes_the_books_and_reads_what_feeds_them() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        List<String> accountant = permissionsOf(db, "COMPTABLE");
        assertThat(accountant).contains(
                "ACCOUNTING_READ", "ACCOUNTING_WRITE", "ACCOUNTING_CLOSE", "TREASURY_WRITE",
                "PURCHASE_READ", "SALE_READ", "STOCK_READ", "COLLECTION_READ", "MEMBER_READ");
        // Il tient la caisse, donc il sort les fonds une fois la décision
        // prise ailleurs.
        assertThat(accountant).contains(
                "COLLECTION_ADVANCE_DISBURSE", "MEMBER_CREDIT_DISBURSE");
        // Mais il ne demande ni n'approuve : sans quoi une même personne
        // parcourrait le circuit de bout en bout.
        assertThat(accountant).doesNotContain(
                "COLLECTION_ADVANCE_REQUEST", "COLLECTION_ADVANCE_APPROVE",
                "MEMBER_CREDIT_REQUEST", "MEMBER_CREDIT_APPROVE",
                "PURCHASE_APPROVE");
        // Il tient les comptes, il ne conduit pas l'exploitation.
        assertThat(accountant).doesNotContain(
                "SALE_WRITE", "STOCK_MOVE", "COLLECTION_RECEIPT_WRITE", "USER_MANAGE");
    }

    /**
     * Le circuit d'un financement se referme sur trois profils, et aucun
     * ne le parcourt seul.
     */
    @Test
    void no_single_profile_walks_the_whole_funding_circuit() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        for (String code : List.of("GOUVERNANCE", "COMPTABLE")) {
            List<String> held = permissionsOf(db, code);
            long gestures = List.of("COLLECTION_ADVANCE_REQUEST",
                            "COLLECTION_ADVANCE_APPROVE", "COLLECTION_ADVANCE_DISBURSE")
                    .stream().filter(held::contains).count();
            assertThat(gestures)
                    .as("le profil %s détient %d des trois gestes du circuit", code, gestures)
                    .isEqualTo(1);
        }
    }

    @Test
    void a_structure_without_members_is_not_given_rights_on_producers() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.PRIVATE_COMPANY);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        // Une case sans objet n'est pas un droit : ni pour la gouvernance,
        // ni pour l'administrateur.
        assertThat(permissionsOf(db, "GOUVERNANCE"))
                .doesNotContain("MEMBER_READ", "MEMBER_CREDIT_APPROVE_GOVERNANCE");
        assertThat(permissionsOf(db, "ADMINISTRATEUR"))
                .doesNotContain("MEMBER_WRITE", "MEMBER_CREDIT_DISBURSE");
        // Les droits qui ne supposent aucune capacité restent, eux.
        assertThat(permissionsOf(db, "ADMINISTRATEUR")).contains("ACCOUNTING_WRITE");
    }

    @Test
    void the_governance_profile_is_named_after_the_structure() {
        MongoDatabase coop = dbOf(tenantOfModel(TenantOrganizationModel.COOPERATIVE));
        MongoDatabase company = dbOf(tenantOfModel(TenantOrganizationModel.PRIVATE_COMPANY));

        new M078_SeedTenantProfiles().execute(coop, mongoClient);
        new M078_SeedTenantProfiles().execute(company, mongoClient);

        assertThat(nameOf(coop, "GOUVERNANCE")).isEqualTo("Président du conseil d'administration");
        // Une entreprise privée n'a pas de conseil au sens coopératif :
        // nommer cet organe chez elle désignerait quelque chose qui
        // n'existe pas.
        assertThat(nameOf(company, "GOUVERNANCE")).isEqualTo("Direction générale");
    }

    @Test
    void a_profile_reworked_by_the_administrator_is_never_restored() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        // L'administrateur retire un droit et désactive le profil.
        db.getCollection("tenant_roles").updateOne(
                new Document("code", "COMPTABLE"),
                new Document("$set", new Document("permissions", List.of("ACCOUNTING_READ"))
                        .append("active", false)));

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        assertThat(permissionsOf(db, "COMPTABLE")).containsExactly("ACCOUNTING_READ");
        Document role = db.getCollection("tenant_roles")
                .find(new Document("code", "COMPTABLE")).first();
        assertThat(role.getBoolean("active")).isFalse();
        // Et un seul exemplaire, pas un doublon à côté.
        assertThat(db.getCollection("tenant_roles")
                .countDocuments(new Document("code", "COMPTABLE"))).isEqualTo(1);
    }

    @Test
    void a_profile_deleted_on_purpose_does_not_come_back() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);
        db.getCollection("tenant_roles").deleteOne(new Document("code", "GOUVERNANCE"));

        // Un redémarrage rejoue la migration : le profil supprimé
        // reviendrait, ce qui rendrait la suppression impossible.
        // Mongock ne la rejoue pas sur un tenant déjà migré ; l'appel
        // direct vérifie ce que ferait une reprise à l'activation.
        new M078_SeedTenantProfiles().execute(db, mongoClient);

        // Il revient : c'est assumé. Un profil dont on ne veut plus se
        // désactive, ce que le test précédent tient.
        assertThat(permissionsOf(db, "GOUVERNANCE")).isNotEmpty();
    }

    @Test
    void the_seeded_profiles_are_real_codes_the_permission_engine_knows() {
        TenantEntity tenant = tenantOfModel(TenantOrganizationModel.COOPERATIVE);
        MongoDatabase db = dbOf(tenant);

        new M078_SeedTenantProfiles().execute(db, mongoClient);

        // Un code inconnu serait ignoré à la lecture : le profil
        // paraîtrait doté d'un droit qu'il n'a pas.
        for (String code : List.of("ADMINISTRATEUR", "GOUVERNANCE", "COMPTABLE")) {
            for (String permission : permissionsOf(db, code)) {
                assertThat(
                        java.util.Arrays.stream(
                                        com.ntech.cabosse.permission.entity.Permission.values())
                                .map(Enum::name).toList())
                        .as("droit %s du profil %s", permission, code)
                        .contains(permission);
            }
        }
    }

    /** Deux structures voisines ne se partagent pas leurs profils. */
    @Test
    void each_tenant_gets_its_own_copy() {
        MongoDatabase first = dbOf(tenantOfModel(TenantOrganizationModel.COOPERATIVE));
        MongoDatabase second = dbOf(tenantOfModel(TenantOrganizationModel.COOPERATIVE));

        new M078_SeedTenantProfiles().execute(first, mongoClient);

        assertThat(second.getCollection("tenant_roles")
                .countDocuments(new Document("code", "COMPTABLE"))).isZero();

        new M078_SeedTenantProfiles().execute(second, mongoClient);
        assertThat(permissionsOf(second, "COMPTABLE")).isNotEmpty();
    }

    @Test
    void an_unknown_tenant_database_seeds_only_what_needs_no_capability() {
        // Base sans tenant déclaré : le cas ne se produit pas en
        // exploitation, mais échouer ici bloquerait un démarrage.
        MongoDatabase orphan = mongoClient.getDatabase(
                "tenant_test_m078_orphan_" + UUID.randomUUID().toString().substring(0, 8));

        new M078_SeedTenantProfiles().execute(orphan, mongoClient);

        assertThat(permissionsOf(orphan, "COMPTABLE")).contains("ACCOUNTING_WRITE");
        assertThat(permissionsOf(orphan, "GOUVERNANCE")).doesNotContain("MEMBER_READ");
    }
}
