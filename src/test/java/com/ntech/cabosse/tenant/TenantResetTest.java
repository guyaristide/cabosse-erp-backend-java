package com.ntech.cabosse.tenant;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.tenant.entity.TenantEntity;
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

        resetService.resetToInitialState(tenant.id, tenant.name);

        assertThat(db.getCollection("members").countDocuments()).isZero();
        assertThat(db.getCollection("producer_purchases").countDocuments()).isZero();
        assertThat(db.getCollection("journal_pieces").countDocuments()).isZero();
    }

    @Test
    void the_profiles_come_back_with_the_same_identifiers() {
        UUID comptable = seedProfile("COMPTABLE");
        UUID operateur = seedProfile("OPERATEUR");

        resetService.resetToInitialState(tenant.id, tenant.name);

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

        resetService.resetToInitialState(tenant.id, tenant.name);

        // Ils vivent dans le plan de contrôle : la base de la structure
        // peut disparaître entièrement sans les emporter.
        assertThat(users.find("tenantId", tenant.id).count()).isEqualTo(before);
    }

    @Test
    void the_structure_stays_usable_after_the_reset() {
        resetService.resetToInitialState(tenant.id, tenant.name);

        // Un site, sinon plus aucune saisie n'est possible.
        assertThat(db.getCollection("sites").countDocuments()).isEqualTo(1);
        // Le plan comptable est reconstruit par les migrations.
        assertThat(db.getCollection("chart_of_accounts").countDocuments()).isPositive();
    }

    @Test
    void a_mistyped_name_destroys_nothing() {
        seedBusinessData();

        assertThatThrownBy(() -> resetService.resetToInitialState(tenant.id, "pas le bon nom"))
                .isInstanceOf(RuntimeException.class);

        assertThat(db.getCollection("members").countDocuments()).isEqualTo(1);
    }
}
