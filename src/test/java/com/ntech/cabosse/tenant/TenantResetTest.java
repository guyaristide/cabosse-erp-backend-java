package com.ntech.cabosse.tenant;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.service.TenantDataCategory;
import com.ntech.cabosse.tenant.service.TenantResetService;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remettre les données à plat, sans emporter ce qui rend la structure
 * utilisable.
 *
 * <p>Ce que ces tests tiennent : que les données d'exploitation
 * disparaissent réellement, que les profils de droits reviennent
 * <strong>avec leurs identifiants d'origine</strong> — les comptes
 * utilisateurs les référencent, et les régénérer priverait chaque
 * collaborateur de ses accès sans le dire — et qu'un nom mal recopié
 * n'efface rien du tout.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantResetTest extends AbstractIntegrationTest {

    @Inject TenantResetService resetService;

    private TenantEntity tenant;
    private MongoDatabase db;

    @BeforeEach
    void setUp() {
        tenant = fixtures.createActiveTenant(
                "coop-reset-" + TestFixtures.randomSlugSuffix(), "Structure Remise À Plat");
        db = mongoClient.getDatabase(tenant.databaseName);
    }

    private UUID seedProfile(String code) {
        UUID id = UUID.randomUUID();
        db.getCollection("tenant_roles").insertOne(new Document("_id", id)
                .append("code", code).append("name", "Profil " + code)
                .append("permissions", List.of("MEMBER_READ")).append("active", true));
        return id;
    }

    private void seedBusinessData() {
        db.getCollection("members").insertOne(
                new Document("_id", UUID.randomUUID()).append("name", "Kouassi"));
        db.getCollection("producer_purchases").insertOne(
                new Document("_id", UUID.randomUUID()).append("ref", "ACH-1"));
        db.getCollection("journal_pieces").insertOne(
                new Document("_id", UUID.randomUUID()).append("ref", "EC-1"));
    }

    @Test
    void the_operating_data_is_really_gone() {
        seedBusinessData();

        resetService.resetToInitialState(tenant.id, tenant.name, Set.of());

        assertThat(db.getCollection("members").countDocuments()).isZero();
        assertThat(db.getCollection("producer_purchases").countDocuments()).isZero();
        assertThat(db.getCollection("journal_pieces").countDocuments()).isZero();
    }

    @Test
    void the_profiles_come_back_with_the_same_identifiers() {
        UUID comptable = seedProfile("COMPTABLE");
        UUID operateur = seedProfile("OPERATEUR");

        resetService.resetToInitialState(tenant.id, tenant.name, Set.of());

        // Les comptes utilisateurs pointent sur ces identifiants : de
        // nouveaux profils, même bien nommés, seraient des profils que
        // plus personne ne porte.
        var ids = db.getCollection("tenant_roles").find()
                .into(new java.util.ArrayList<>()).stream()
                .map(d -> d.get("_id")).toList();
        assertThat(ids).contains(comptable, operateur);
    }

    @Test
    void the_user_accounts_are_untouched() {
        long before = users.find("tenantId", tenant.id).count();

        resetService.resetToInitialState(tenant.id, tenant.name, Set.of());

        // Ils vivent dans le plan de contrôle : la base de la structure
        // peut disparaître entièrement sans les emporter.
        assertThat(users.find("tenantId", tenant.id).count()).isEqualTo(before);
    }

    @Test
    void the_structure_stays_usable_after_the_reset() {
        resetService.resetToInitialState(tenant.id, tenant.name, Set.of());

        // Un site, sinon plus aucune saisie n'est possible.
        assertThat(db.getCollection("sites").countDocuments()).isEqualTo(1);
        // Le plan comptable est reconstruit par les migrations.
        assertThat(db.getCollection("chart_of_accounts").countDocuments()).isPositive();
    }

    /** Une nomenclature saisie par la structure, reconnaissable après coup. */
    private UUID seedNomenclature() {
        UUID id = UUID.randomUUID();
        db.getCollection("expense_types").insertOne(new Document("_id", id)
                .append("code", "TRANSPORT").append("name", "Transport de fèves"));
        return id;
    }

    /** Un producteur et sa parcelle : le registre qu'on ne veut pas réimporter. */
    private UUID seedParty() {
        UUID id = UUID.randomUUID();
        db.getCollection("members").insertOne(new Document("_id", id)
                .append("code", "P-001").append("lastName", "Tiemoko"));
        return id;
    }

    /** Un réglage de la structure : un exercice ouvert. */
    private UUID seedSetting() {
        UUID id = UUID.randomUUID();
        db.getCollection("fiscal_years").insertOne(new Document("_id", id)
                .append("label", "2026").append("closed", false));
        return id;
    }

    private boolean stillThere(String collection, UUID id) {
        return db.getCollection(collection).countDocuments(new Document("_id", id)) == 1;
    }

    @Test
    void les_nomenclatures_se_conservent_seules() {
        UUID expenseType = seedNomenclature();
        UUID member = seedParty();
        UUID fiscalYear = seedSetting();
        seedBusinessData();

        resetService.resetToInitialState(
                tenant.id, tenant.name, Set.of(TenantDataCategory.NOMENCLATURES));

        assertThat(stillThere("expense_types", expenseType)).isTrue();
        // Les deux autres familles n'ont pas été demandées : elles partent,
        // et les opérations avec elles.
        assertThat(stillThere("members", member)).isFalse();
        assertThat(stillThere("fiscal_years", fiscalYear)).isFalse();
        assertThat(db.getCollection("producer_purchases").countDocuments()).isZero();
    }

    @Test
    void le_registre_des_tiers_se_conserve_seul() {
        UUID member = seedParty();
        UUID expenseType = seedNomenclature();
        seedBusinessData();

        resetService.resetToInitialState(
                tenant.id, tenant.name, Set.of(TenantDataCategory.PARTIES));

        // Quatre mille producteurs importés ne se réimportent pas pour
        // jeter une saison d'écritures.
        assertThat(stillThere("members", member)).isTrue();
        assertThat(stillThere("expense_types", expenseType)).isFalse();
        assertThat(db.getCollection("journal_pieces").countDocuments()).isZero();
    }

    @Test
    void conserver_le_parametrage_garde_les_sites_en_place() {
        UUID fiscalYear = seedSetting();
        UUID site = UUID.randomUUID();
        db.getCollection("sites").insertOne(new Document("_id", site)
                .append("name", "Méagui").append("code", "meagui").append("active", true));

        resetService.resetToInitialState(
                tenant.id, tenant.name, Set.of(TenantDataCategory.SETTINGS));

        assertThat(stillThere("fiscal_years", fiscalYear)).isTrue();
        // Le site par défaut ne doit pas s'ajouter à ceux qu'on a gardés :
        // la structure se retrouverait avec un « Siège » qu'elle n'a
        // jamais créé.
        assertThat(db.getCollection("sites").countDocuments()).isEqualTo(1);
        assertThat(stillThere("sites", site)).isTrue();
    }

    @Test
    void les_operations_partent_meme_quand_tout_le_reste_est_garde() {
        seedBusinessData();
        UUID member = seedParty();

        resetService.resetToInitialState(tenant.id, tenant.name, Set.of(
                TenantDataCategory.SETTINGS,
                TenantDataCategory.NOMENCLATURES,
                TenantDataCategory.PARTIES));

        // Effacer les opérations est la raison même de l'opération : aucune
        // combinaison de cases ne peut les épargner.
        assertThat(db.getCollection("producer_purchases").countDocuments()).isZero();
        assertThat(db.getCollection("journal_pieces").countDocuments()).isZero();
        assertThat(stillThere("members", member)).isTrue();
    }

    @Test
    void le_plan_comptable_conserve_ne_revient_pas_en_double() {
        // Les migrations sèment le plan SYSCOHADA à chaque reconstruction.
        // Conservé, c'est le plan de la structure qui fait foi : le semis
        // doit être écarté, pas ajouté par-dessus.
        db.getCollection("chart_of_accounts").deleteMany(new Document());
        UUID account = UUID.randomUUID();
        db.getCollection("chart_of_accounts").insertOne(new Document("_id", account)
                .append("number", "601100").append("label", "Achats de fèves"));

        resetService.resetToInitialState(
                tenant.id, tenant.name, Set.of(TenantDataCategory.NOMENCLATURES));

        assertThat(db.getCollection("chart_of_accounts").countDocuments()).isEqualTo(1);
        assertThat(stillThere("chart_of_accounts", account)).isTrue();
    }

    @Test
    void a_mistyped_name_destroys_nothing() {
        seedBusinessData();

        assertThatThrownBy(() -> resetService.resetToInitialState(tenant.id, "pas le bon nom", Set.of()))
                .isInstanceOf(RuntimeException.class);

        assertThat(db.getCollection("members").countDocuments()).isEqualTo(1);
    }
}
