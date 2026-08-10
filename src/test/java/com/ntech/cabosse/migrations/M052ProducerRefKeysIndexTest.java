package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
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
 * Index des clés de rapprochement producteur.
 *
 * <p>Le cas qui a bloqué la démo : deux producteurs sans carte. Leur liste
 * de clés est vide, Mongo l'indexe sous une clé commune, et la création de
 * l'index unique échouait. Sur un tenant où la migration n'était pas
 * passée, cet échec abandonnait toute la chaîne et les migrations
 * suivantes ne s'exécutaient jamais.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M052ProducerRefKeysIndexTest extends AbstractIntegrationTest {

    private MongoDatabase db(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m052_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Document memberWithoutCard(String name) {
        return new Document("_id", UUID.randomUUID())
                .append("name", name)
                .append("code", "M-" + name)
                .append("producerRefKeys", List.of());
    }

    @Test
    void two_producers_without_a_card_do_not_break_the_index() {
        MongoDatabase database = db("empty");
        database.getCollection("members").insertMany(List.of(
                memberWithoutCard("Kouassi"), memberWithoutCard("Yao"), memberWithoutCard("Bamba")));

        // Les options posées par M052, sur les données qui la faisaient
        // échouer. C'est cette création d'index qui abandonnait la chaîne.
        database.getCollection("members").createIndex(
                Indexes.ascending("producerRefKeys"),
                M052_ProducerCardsAsDocuments.producerRefKeysIndexOptions(false));

        Document index = indexNamed(database, "uniq_members_producerRefKeys");
        assertThat(index).as("l'index unique doit exister").isNotNull();
        assertThat(index.get("partialFilterExpression"))
                .as("il ne couvre que les producteurs porteurs d'une clé")
                .isNotNull();

        // Un quatrième producteur sans carte passe toujours.
        database.getCollection("members").insertOne(memberWithoutCard("Diabate"));
        // Et l'unicité vaut là où elle a un sens.
        database.getCollection("members").insertOne(new Document("_id", UUID.randomUUID())
                .append("name", "Traore").append("producerRefKeys", List.of("CCC123")));
        assertThat(database.getCollection("members").countDocuments()).isEqualTo(5);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.mongodb.MongoWriteException.class,
                () -> database.getCollection("members").insertOne(new Document("_id", UUID.randomUUID())
                        .append("name", "Sosie").append("producerRefKeys", List.of("CCC123"))),
                "deux producteurs ne peuvent pas porter la même carte");
    }

    @Test
    void an_index_created_in_the_old_form_is_replaced() {
        MongoDatabase database = db("repair");
        database.getCollection("members").insertOne(memberWithoutCard("Solo"));
        // L'état laissé par la version initiale de M052.
        database.getCollection("members").createIndex(
                Indexes.ascending("producerRefKeys"),
                new IndexOptions().name("uniq_members_producerRefKeys").unique(true).sparse(true));

        new M060_RepairProducerRefKeysIndex().execute(database);

        Document index = indexNamed(database, "uniq_members_producerRefKeys");
        assertThat(index).isNotNull();
        assertThat(index.get("partialFilterExpression")).isNotNull();
        assertThat(index.get("sparse")).isNull();

        // Rejouée, la migration laisse l'index en place.
        new M060_RepairProducerRefKeysIndex().execute(database);
        assertThat(indexNamed(database, "uniq_members_producerRefKeys")).isNotNull();
    }

    @Test
    void a_shared_key_keeps_the_index_non_unique() {
        MongoDatabase database = db("dups");
        database.getCollection("members").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("name", "A")
                        .append("producerRefKeys", List.of("CCC999")),
                new Document("_id", UUID.randomUUID()).append("name", "B")
                        .append("producerRefKeys", List.of("CCC999"))));
        database.getCollection("members").createIndex(
                Indexes.ascending("producerRefKeys"),
                new IndexOptions().name("idx_members_producerRefKeys").sparse(true));

        new M060_RepairProducerRefKeysIndex().execute(database);

        assertThat(indexNamed(database, "uniq_members_producerRefKeys"))
                .as("l'unicité ne peut pas être imposée tant que la clé est partagée")
                .isNull();
        Document index = indexNamed(database, "idx_members_producerRefKeys");
        assertThat(index).isNotNull();
        assertThat(index.get("partialFilterExpression")).isNotNull();
    }

    private static Document indexNamed(MongoDatabase database, String name) {
        for (Document index : database.getCollection("members").listIndexes()) {
            if (name.equals(index.getString("name"))) return index;
        }
        return null;
    }
}
