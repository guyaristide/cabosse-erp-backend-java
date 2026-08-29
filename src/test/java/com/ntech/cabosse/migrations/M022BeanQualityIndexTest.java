package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Une base provisionnée avant le changement d'index redémarre quand même.
 *
 * <p>Panne rencontrée en production. La migration 022 pose l'index des
 * contrôles qualité ; la 074 le refait en index partiel, parce qu'un
 * contrôle peut vivre sans lot de séchage. La 022 étant en rejeu, elle
 * repasse à chaque démarrage — et avant la 074, son ordre étant plus
 * petit. Sur une base plus ancienne que ce changement, elle réclamait la
 * forme nouvelle, trouvait l'ancienne, et échouait. La chaîne s'arrêtait
 * là : la 074 qui aurait réparé l'index n'était jamais atteinte, et toutes
 * les migrations suivantes restaient en attente pour toujours. Le tenant
 * n'avait plus ni profils préconfigurés ni rien de ce qui a été livré
 * depuis, sans autre signe qu'une ligne d'erreur au démarrage.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M022BeanQualityIndexTest extends AbstractIntegrationTest {

    private static final String CHECKS = "bean_quality_checks";
    private static final String INDEX = "uniq_qc_dryingBatchId";

    /** Un tenant qui sèche : sans la capacité, la migration ne fait rien. */
    private MongoDatabase databaseOfDryingTenant() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-qc-" + TestFixtures.randomSlugSuffix(), "Structure Qualité");
        tenant.activities = new ArrayList<>(
                List.of(new TenantActivity("cacao-production", "Production de cacao", true)));
        tenants.update(tenant);
        return mongoClient.getDatabase(tenant.databaseName);
    }

    /** L'index tel que les bases d'avant la 074 le portent encore. */
    private static void poseFormeAncienne(MongoDatabase db) {
        db.getCollection(CHECKS).createIndex(
                new Document("dryingBatchId", 1),
                new IndexOptions().name(INDEX).unique(true));
    }

    private static Document index(MongoDatabase db) {
        for (Document i : db.getCollection(CHECKS).listIndexes()) {
            if (INDEX.equals(i.getString("name"))) return i;
        }
        return null;
    }

    @Test
    void a_database_older_than_the_index_change_still_migrates() {
        MongoDatabase db = databaseOfDryingTenant();
        poseFormeAncienne(db);

        assertThatCode(() -> new M022_CreateBeanQualityChecksCollection().execute(db, mongoClient))
                .doesNotThrowAnyException();

        // La forme du code déployé l'emporte : l'absence de lot de séchage
        // cesse d'être une valeur en double.
        assertThat(index(db)).isNotNull();
        assertThat(index(db).get("partialFilterExpression")).isNotNull();
    }

    @Test
    void two_autonomous_quality_checks_can_coexist_once_the_index_is_repaired() {
        MongoDatabase db = databaseOfDryingTenant();
        poseFormeAncienne(db);
        new M022_CreateBeanQualityChecksCollection().execute(db, mongoClient);

        // Ce que l'ancien index interdisait : deux contrôles sur de la
        // matière qui n'est pas sortie d'un séchoir.
        db.getCollection(CHECKS).insertMany(List.of(
                new Document("_id", java.util.UUID.randomUUID()).append("ref", "QC-1"),
                new Document("_id", java.util.UUID.randomUUID()).append("ref", "QC-2")));

        assertThat(db.getCollection(CHECKS).countDocuments()).isEqualTo(2);
    }

    @Test
    void restarting_again_changes_nothing() {
        MongoDatabase db = databaseOfDryingTenant();
        poseFormeAncienne(db);
        var migration = new M022_CreateBeanQualityChecksCollection();

        migration.execute(db, mongoClient);
        assertThatCode(() -> migration.execute(db, mongoClient)).doesNotThrowAnyException();

        assertThat(index(db).get("partialFilterExpression")).isNotNull();
    }
}
