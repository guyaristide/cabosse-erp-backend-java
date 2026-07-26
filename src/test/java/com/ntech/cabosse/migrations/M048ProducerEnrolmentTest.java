package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
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
 * MEM-07 — reprise des fiches membres antérieures. La projection du champ
 * legacy {@code civilStatus} vers {@code gender} / {@code personType} et la
 * recopie de la pièce d'identité unique dans la liste sont écrites en
 * pipeline d'agrégation : elles ne valent que vérifiées contre Mongo.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M048ProducerEnrolmentTest extends AbstractIntegrationTest {

    private MongoCollection<Document> legacyMembers(String dbName) {
        return mongoClient.getDatabase(dbName).getCollection("members");
    }

    @Test
    void legacy_members_are_projected_onto_the_dedicated_fields() {
        String dbName = "tenant_test_m048_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = mongoClient.getDatabase(dbName);

        legacyMembers(dbName).insertMany(List.of(
                new Document("_id", UUID.randomUUID())
                        .append("name", "Konan N'Guessan")
                        .append("civilStatus", "MALE")
                        .append("idDocType", "CNI")
                        .append("idDocNumber", "CI001")
                        .append("idCardFileId", null),
                new Document("_id", UUID.randomUUID())
                        .append("name", "Groupement Bénié")
                        .append("civilStatus", "LEGAL_ENTITY"),
                new Document("_id", UUID.randomUUID())
                        .append("name", "Sans état civil")));

        M048_ProducerEnrolment.backfillMembers(database);

        Document homme = legacyMembers(dbName).find(new Document("name", "Konan N'Guessan")).first();
        assertThat(homme).isNotNull();
        assertThat(homme.getString("gender")).isEqualTo("MALE");
        assertThat(homme.getString("personType")).isEqualTo("NATURAL_PERSON");
        assertThat(homme.getString("maritalStatus")).isEqualTo("UNKNOWN");
        List<?> docs = homme.getList("identityDocuments", Document.class);
        assertThat(docs).hasSize(1);
        assertThat(((Document) docs.get(0)).getString("number")).isEqualTo("CI001");

        Document morale = legacyMembers(dbName).find(new Document("name", "Groupement Bénié")).first();
        assertThat(morale).isNotNull();
        assertThat(morale.getString("personType")).isEqualTo("LEGAL_ENTITY");
        assertThat(morale.getString("gender")).isEqualTo("UNKNOWN");
        assertThat(morale.getList("identityDocuments", Document.class)).isEmpty();

        Document inconnu = legacyMembers(dbName).find(new Document("name", "Sans état civil")).first();
        assertThat(inconnu).isNotNull();
        assertThat(inconnu.getString("gender")).isEqualTo("UNKNOWN");
        assertThat(inconnu.getString("personType")).isEqualTo("NATURAL_PERSON");

        database.drop();
    }

    @Test
    void backfill_is_idempotent_and_preserves_already_migrated_fiches() {
        String dbName = "tenant_test_m048_idem_" + UUID.randomUUID().toString().substring(0, 8);
        MongoDatabase database = mongoClient.getDatabase(dbName);

        legacyMembers(dbName).insertOne(new Document("_id", UUID.randomUUID())
                .append("name", "Déjà migrée")
                .append("civilStatus", "MALE")
                .append("gender", "FEMALE")
                .append("personType", "NATURAL_PERSON")
                .append("maritalStatus", "MARRIED")
                .append("identityDocuments", List.of(
                        new Document("type", "Passeport").append("number", "P42"))));

        M048_ProducerEnrolment.backfillMembers(database);
        M048_ProducerEnrolment.backfillMembers(database);

        Document m = legacyMembers(dbName).find(new Document("name", "Déjà migrée")).first();
        assertThat(m).isNotNull();
        // La fiche a déjà `gender` : la migration ne la touche pas, donc la
        // valeur saisie prime sur la projection du champ legacy.
        assertThat(m.getString("gender")).isEqualTo("FEMALE");
        assertThat(m.getString("maritalStatus")).isEqualTo("MARRIED");
        assertThat(m.getList("identityDocuments", Document.class)).hasSize(1);

        database.drop();
    }
}
