package com.ntech.cabosse.notification;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La boîte de réception se lit par son destinataire, sans tout parcourir.
 *
 * <p>La cloche compte les non-lus toutes les soixante secondes, par
 * onglet et par utilisateur. Sa requête porte sur {@code channel} et
 * {@code target} ; aucun index ne portait le second, donc chaque passage
 * parcourait toute la messagerie de la structure.</p>
 *
 * <p>Ce test interroge le plan d'exécution de Mongo plutôt que la
 * présence de l'index : un index peut exister et n'être pas retenu, et
 * c'est le parcours qui coûte, pas la déclaration.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class InboxIndexTest extends AbstractIntegrationTest {

    @Inject MongoClient mongoClient;
    @Inject TenantMigrationRunner migrations;

    @Test
    void le_compteur_de_non_lus_passe_par_un_index() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-inbox-" + TestFixtures.randomSlugSuffix(), "Coopérative Boîte");
        migrations.runMigrationsFor(tenant.databaseName);

        var deliveries = mongoClient.getDatabase(tenant.databaseName)
                .getCollection("notification_deliveries");

        // Du bruit : d'autres destinataires, et un autre canal. C'est eux
        // que le parcours lisait pour rien.
        for (int i = 0; i < 200; i++) {
            deliveries.insertOne(new Document("_id", java.util.UUID.randomUUID())
                    .append("channel", i % 2 == 0 ? "IN_APP" : "EMAIL")
                    .append("target", "autre" + i + "@coop.ci")
                    .append("createdAt", java.time.Instant.now()));
        }
        deliveries.insertOne(new Document("_id", java.util.UUID.randomUUID())
                .append("channel", "IN_APP")
                .append("target", "moi@coop.ci")
                .append("createdAt", java.time.Instant.now()));

        Document plan = deliveries.find(Filters.and(
                        Filters.eq("channel", "IN_APP"),
                        Filters.eq("target", "moi@coop.ci"),
                        Filters.exists("readAt", false)))
                .explain();

        String stage = plan.toJson();
        assertThat(stage)
                .as("le compteur de non-lus doit passer par un index, pas par un parcours")
                .contains("IXSCAN");

        Document stats = (Document) plan.get("executionStats");
        if (stats != null) {
            long examined = ((Number) stats.get("totalDocsExamined")).longValue();
            // Un seul message pour ce destinataire : en lire deux cents de
            // plus était tout le problème.
            assertThat(examined)
                    .as("documents lus pour une boîte d'un seul message")
                    .isLessThanOrEqualTo(5);
        }
    }
}
