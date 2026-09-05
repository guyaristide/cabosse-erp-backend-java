package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Remise en décimal des montants stockés dans un autre type numérique.
 *
 * <p>Le cas qui compte : le zéro entier posé par M051 sur la rémunération
 * des délégués, qui rendait tout reçu antérieur illisible et faisait
 * tomber la liste entière des achats producteurs.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M059NormalizeDecimalAmountsTest extends AbstractIntegrationTest {

    private MongoDatabase db(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m059_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void root_amounts_of_every_numeric_type_become_decimals() {
        MongoDatabase database = db("root");
        UUID id = UUID.randomUUID();
        database.getCollection("producer_purchases").insertOne(new Document("_id", id)
                .append("ref", "AP-2026-0001")
                .append("delegateMargin", 0)                       // int, cas M051
                .append("amountPaid", 100000L)                     // long
                .append("creditImputed", 2500.5d)                  // double
                .append("amount", new Decimal128(new BigDecimal("400000")))
                .append("nbSacs", 8));                                 // entier légitime

        new M059_NormalizeDecimalAmounts().execute(database);

        Document fixed = database.getCollection("producer_purchases")
                .find(new Document("_id", id)).first();
        assertThat(fixed).isNotNull();
        assertThat(fixed.get("delegateMargin")).isInstanceOf(Decimal128.class);
        assertThat(fixed.get("amountPaid")).isInstanceOf(Decimal128.class);
        assertThat(fixed.get("creditImputed")).isInstanceOf(Decimal128.class);
        // Les valeurs ne changent pas, seul leur type.
        assertThat(((Decimal128) fixed.get("amountPaid")).bigDecimalValue())
                .isEqualByComparingTo("100000");
        assertThat(((Decimal128) fixed.get("creditImputed")).bigDecimalValue())
                .isEqualByComparingTo("2500.5");
        // Un montant déjà correct est laissé tel quel.
        assertThat(((Decimal128) fixed.get("amount")).bigDecimalValue())
                .isEqualByComparingTo("400000");
        // Un entier qui n'est pas un montant reste un entier.
        assertThat(fixed.get("nbSacs")).isInstanceOf(Integer.class);
    }

    @Test
    void amounts_nested_in_arrays_are_converted_too() {
        MongoDatabase database = db("nested");
        UUID id = UUID.randomUUID();
        database.getCollection("journal_pieces").insertOne(new Document("_id", id)
                .append("ref", "PC-2026-0001")
                .append("entries", List.of(
                        new Document("syscohadaAccount", "601000").append("debit", 50000),
                        new Document("syscohadaAccount", "401000").append("credit", 50000L))));

        new M059_NormalizeDecimalAmounts().execute(database);

        Document piece = database.getCollection("journal_pieces")
                .find(new Document("_id", id)).first();
        assertThat(piece).isNotNull();
        @SuppressWarnings("unchecked")
        List<Document> entries = (List<Document>) piece.get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("debit")).isInstanceOf(Decimal128.class);
        assertThat(entries.get(1).get("credit")).isInstanceOf(Decimal128.class);
        // Le compte, lui, n'a pas bougé.
        assertThat(entries.get(0).getString("syscohadaAccount")).isEqualTo("601000");
    }

    @Test
    void a_clean_collection_is_left_untouched_and_the_run_is_repeatable() {
        MongoDatabase database = db("clean");
        UUID id = UUID.randomUUID();
        database.getCollection("member_credits").insertOne(new Document("_id", id)
                .append("ref", "CR-2026-0001")
                .append("amount", new Decimal128(new BigDecimal("150000")))
                .append("remaining", new Decimal128(new BigDecimal("120000"))));

        new M059_NormalizeDecimalAmounts().execute(database);
        // Rejouée, la migration ne doit rien casser.
        new M059_NormalizeDecimalAmounts().execute(database);

        Document credit = database.getCollection("member_credits")
                .find(new Document("_id", id)).first();
        assertThat(credit).isNotNull();
        assertThat(((Decimal128) credit.get("amount")).bigDecimalValue())
                .isEqualByComparingTo("150000");
        assertThat(((Decimal128) credit.get("remaining")).bigDecimalValue())
                .isEqualByComparingTo("120000");
    }

    @Test
    void an_absent_array_is_not_created() {
        MongoDatabase database = db("absent");
        UUID id = UUID.randomUUID();
        database.getCollection("producer_payments").insertOne(new Document("_id", id)
                .append("ref", "REG-2026-0001")
                .append("totalAmount", 42));

        new M059_NormalizeDecimalAmounts().execute(database);

        Document payment = database.getCollection("producer_payments")
                .find(new Document("_id", id)).first();
        assertThat(payment).isNotNull();
        assertThat(payment.get("totalAmount")).isInstanceOf(Decimal128.class);
        assertThat(payment.containsKey("allocations")).isFalse();
    }
}
