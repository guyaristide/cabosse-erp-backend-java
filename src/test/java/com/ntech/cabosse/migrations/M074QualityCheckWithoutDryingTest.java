package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
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
 * Un contrôle qualité peut vivre sans lot de séchage.
 *
 * <p>Un index unique sur un champ absent range tous ces documents sous une
 * même clé nulle et refuse le deuxième contrôle autonome. La règle métier
 * gardée reste la bonne : un lot ne porte qu'un seul contrôle.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M074QualityCheckWithoutDryingTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m074_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static void poseAncienIndex(MongoDatabase db) {
        db.getCollection("bean_quality_checks").createIndex(
                new Document("dryingBatchId", 1),
                new IndexOptions().unique(true).name("uniq_qc_dryingBatchId"));
    }

    private static Document check(UUID dryingBatchId) {
        return new Document("_id", UUID.randomUUID()).append("dryingBatchId", dryingBatchId);
    }

    @Test
    void several_checks_without_a_drying_batch_can_coexist() {
        MongoDatabase database = freshDatabase("autonomes");
        poseAncienIndex(database);

        new M074_QualityCheckWithoutDrying().execute(database);

        database.getCollection("bean_quality_checks").insertMany(List.of(check(null), check(null)));

        assertThat(database.getCollection("bean_quality_checks").countDocuments()).isEqualTo(2);
    }

    @Test
    void one_drying_batch_still_carries_one_check_only() {
        MongoDatabase database = freshDatabase("unicite");
        new M074_QualityCheckWithoutDrying().execute(database);

        UUID batch = UUID.randomUUID();
        database.getCollection("bean_quality_checks").insertOne(check(batch));

        // Deux verdicts sur la même matière ne se départagent pas.
        try {
            database.getCollection("bean_quality_checks").insertOne(check(batch));
            org.junit.jupiter.api.Assertions.fail("un second contrôle sur le même lot aurait dû être refusé");
        } catch (com.mongodb.MongoWriteException expected) {
            assertThat(expected.getMessage()).contains("uniq_qc_dryingBatchId");
        }
    }

    @Test
    void replaying_the_migration_changes_nothing() {
        MongoDatabase database = freshDatabase("rejeu");
        poseAncienIndex(database);

        new M074_QualityCheckWithoutDrying().execute(database);
        new M074_QualityCheckWithoutDrying().execute(database);

        database.getCollection("bean_quality_checks").insertMany(List.of(check(null), check(null)));
        assertThat(database.getCollection("bean_quality_checks").countDocuments()).isEqualTo(2);
    }
}
