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
 * Le négoce cesse de s'appeler cacao, sans perdre son historique.
 *
 * <p>La partie qui compte n'est pas le nom de la collection mais les
 * <strong>valeurs d'énumération persistées hors du domaine</strong> : la
 * source d'un mouvement de stock et l'origine d'une pièce comptable.
 * Laissées en l'état, elles ne se relisent plus, et c'est tout
 * l'historique de stock et de comptabilité qui devient illisible.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M076RenameCommodityTradeTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m076_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static List<String> indexNames(MongoDatabase db, String collection) {
        return db.getCollection(collection).listIndexes()
                .into(new java.util.ArrayList<>()).stream()
                .map(d -> d.getString("name")).toList();
    }

    @Test
    void the_sales_move_to_their_new_collection_with_new_indexes() {
        MongoDatabase database = freshDatabase("collection");
        UUID sale = UUID.randomUUID();
        database.getCollection("cacao_sales").insertOne(
                new Document("_id", sale).append("ref", "VC-2026-0001"));
        database.getCollection("cacao_sales").createIndex(
                new Document("ref", 1), new IndexOptions().unique(true).name("uniq_cacao_sales_ref"));

        new M076_RenameCommodityTrade().execute(database, mongoClient);

        assertThat(database.getCollection("commodity_sales").countDocuments()).isEqualTo(1);
        assertThat(database.getCollection("commodity_sales")
                .find(new Document("_id", sale)).first().getString("ref")).isEqualTo("VC-2026-0001");
        // Un renommage de collection garde les index sous leur ancien nom :
        // les laisser laisserait « cacao » en base sans que rien ne le montre.
        assertThat(indexNames(database, "commodity_sales"))
                .contains("uniq_commodity_sales_ref")
                .doesNotContain("uniq_cacao_sales_ref");
    }

    @Test
    void the_reference_counter_follows_so_numbering_does_not_restart() {
        MongoDatabase database = freshDatabase("compteur");
        database.getCollection("counters").insertOne(
                new Document("_id", "cacao_sale:2026").append("value", 42));

        new M076_RenameCommodityTrade().execute(database, mongoClient);

        // Sans reprise, la référence suivante entrerait en collision avec
        // une vente existante, que l'index d'unicité refuserait.
        Document moved = database.getCollection("counters")
                .find(new Document("_id", "commodity_sale:2026")).first();
        assertThat(moved).isNotNull();
        assertThat(moved.getInteger("value")).isEqualTo(42);
        assertThat(database.getCollection("counters")
                .find(new Document("_id", "cacao_sale:2026")).first()).isNull();
    }

    @Test
    void the_stock_and_accounting_history_stays_readable() {
        MongoDatabase database = freshDatabase("historique");
        database.getCollection("stock_movements").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("source", "CACAO_SALE"),
                new Document("_id", UUID.randomUUID()).append("source", "PRODUCER_PURCHASE")));
        database.getCollection("journal_pieces").insertOne(
                new Document("_id", UUID.randomUUID()).append("sourceType", "CACAO_SALE"));

        new M076_RenameCommodityTrade().execute(database, mongoClient);

        assertThat(database.getCollection("stock_movements")
                .countDocuments(new Document("source", "COMMODITY_SALE"))).isEqualTo(1);
        // Les autres sources ne bougent pas.
        assertThat(database.getCollection("stock_movements")
                .countDocuments(new Document("source", "PRODUCER_PURCHASE"))).isEqualTo(1);
        assertThat(database.getCollection("journal_pieces")
                .countDocuments(new Document("sourceType", "COMMODITY_SALE"))).isEqualTo(1);
        assertThat(database.getCollection("stock_movements")
                .countDocuments(new Document("source", "CACAO_SALE"))).isZero();
    }

    @Test
    void replaying_the_migration_changes_nothing() {
        MongoDatabase database = freshDatabase("rejeu");
        database.getCollection("cacao_sales").insertOne(
                new Document("_id", UUID.randomUUID()).append("ref", "VC-2026-0001"));
        database.getCollection("stock_movements").insertOne(
                new Document("_id", UUID.randomUUID()).append("source", "CACAO_SALE"));

        new M076_RenameCommodityTrade().execute(database, mongoClient);
        new M076_RenameCommodityTrade().execute(database, mongoClient);

        assertThat(database.getCollection("commodity_sales").countDocuments()).isEqualTo(1);
        assertThat(database.getCollection("stock_movements")
                .countDocuments(new Document("source", "COMMODITY_SALE"))).isEqualTo(1);
    }

    @Test
    void a_partial_run_pours_the_remainder_instead_of_failing() {
        MongoDatabase database = freshDatabase("partiel");
        UUID ancienne = UUID.randomUUID();
        UUID deja = UUID.randomUUID();
        database.getCollection("cacao_sales").insertOne(
                new Document("_id", ancienne).append("ref", "VC-2026-0001"));
        database.getCollection("commodity_sales").insertOne(
                new Document("_id", deja).append("ref", "VC-2026-0002"));

        // Une reprise interrompue laisse les deux collections en place :
        // échouer sur un nom déjà pris bloquerait le démarrage.
        new M076_RenameCommodityTrade().execute(database, mongoClient);

        assertThat(database.getCollection("commodity_sales").countDocuments()).isEqualTo(2);
        assertThat(database.listCollectionNames().into(new java.util.ArrayList<>()))
                .doesNotContain("cacao_sales");
    }
}
