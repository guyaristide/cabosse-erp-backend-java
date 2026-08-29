package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
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
 * Le droit unique sur les avances se scinde en trois.
 *
 * <p>Ce que la migration doit garantir : les profils qui créaient une
 * avance ne reçoivent <strong>que</strong> le droit de la demander. Leur
 * accorder aussi l'approbation reviendrait à défaire le découpage au
 * moment même de le poser.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M077SplitAdvancePermissionsTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m077_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @SuppressWarnings("unchecked")
    private static List<String> permissionsOf(MongoDatabase db, UUID roleId) {
        Document role = db.getCollection("tenant_roles").find(new Document("_id", roleId)).first();
        return role == null ? List.of() : (List<String>) role.get("permissions");
    }

    @Test
    void the_old_right_becomes_the_right_to_request_and_nothing_more() {
        MongoDatabase database = freshDatabase("scission");
        UUID gestionnaire = UUID.randomUUID();
        database.getCollection("tenant_roles").insertOne(new Document("_id", gestionnaire)
                .append("name", "Gestionnaire collecte")
                .append("permissions", List.of("COLLECTION_READ", "COLLECTION_ADVANCE_WRITE")));

        new M077_SplitAdvancePermissions().execute(database);

        assertThat(permissionsOf(database, gestionnaire))
                .contains("COLLECTION_READ", "COLLECTION_ADVANCE_REQUEST")
                // Le découpage ne vaudrait rien si l'approbation suivait.
                .doesNotContain("COLLECTION_ADVANCE_APPROVE",
                        "COLLECTION_ADVANCE_DISBURSE", "COLLECTION_ADVANCE_WRITE");
    }

    @Test
    void a_profile_without_the_old_right_gains_nothing() {
        MongoDatabase database = freshDatabase("intact");
        UUID lecteur = UUID.randomUUID();
        database.getCollection("tenant_roles").insertOne(new Document("_id", lecteur)
                .append("name", "Lecture seule")
                .append("permissions", List.of("COLLECTION_READ", "MEMBER_READ")));

        new M077_SplitAdvancePermissions().execute(database);

        assertThat(permissionsOf(database, lecteur))
                .containsExactlyInAnyOrder("COLLECTION_READ", "MEMBER_READ");
    }

    @Test
    void replaying_the_migration_does_not_duplicate_the_right() {
        MongoDatabase database = freshDatabase("rejeu");
        UUID role = UUID.randomUUID();
        database.getCollection("tenant_roles").insertOne(new Document("_id", role)
                .append("name", "Gestionnaire")
                .append("permissions", List.of("COLLECTION_ADVANCE_WRITE")));

        new M077_SplitAdvancePermissions().execute(database);
        new M077_SplitAdvancePermissions().execute(database);

        assertThat(permissionsOf(database, role))
                .containsExactly("COLLECTION_ADVANCE_REQUEST");
    }

    @Test
    void a_profile_holding_both_forms_keeps_a_single_entry() {
        MongoDatabase database = freshDatabase("mixte");
        UUID role = UUID.randomUUID();
        // Reprise partielle interrompue : les deux codes coexistent.
        database.getCollection("tenant_roles").insertOne(new Document("_id", role)
                .append("name", "Gestionnaire")
                .append("permissions",
                        List.of("COLLECTION_ADVANCE_WRITE", "COLLECTION_ADVANCE_REQUEST")));

        new M077_SplitAdvancePermissions().execute(database);

        assertThat(permissionsOf(database, role))
                .containsExactly("COLLECTION_ADVANCE_REQUEST");
    }
}
