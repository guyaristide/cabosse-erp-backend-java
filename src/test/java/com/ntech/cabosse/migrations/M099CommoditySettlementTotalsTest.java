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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Les totaux du décompte se calculent des composantes (VTE-26) : une
 * vente qui portait des totaux recopiés reçoit les siens, une vente sans
 * composante n'en a pas.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M099CommoditySettlementTotalsTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase() {
        return mongoClient.getDatabase(
                "tenant_test_m099_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Document settlement(Document... fields) {
        Document d = new Document();
        for (Document f : fields) d.putAll(f);
        return d;
    }

    private static Document f(String k, String v) { return new Document(k, new Decimal128(new BigDecimal(v))); }

    @Test
    void les_totaux_viennent_des_composantes_et_du_facture() {
        MongoDatabase db = freshDatabase();
        UUID withParts = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        db.getCollection("commodity_sales").insertOne(new Document("_id", withParts)
                .append("amountInvoicedTtc", new Decimal128(new BigDecimal("18630000")))
                .append("settlement", settlement(f("transportEqualization", "350000"),
                        f("bic", "10000"), f("fiscalStamp", "10000"), f("salesCommission", "10000"),
                        f("mandateRepayment", "10000"), f("revolvingRepayment", "10000"),
                        // Les totaux recopiés, faux : ils sont remplacés.
                        f("totalValue", "25050000"), f("netAmount", "25000000"))));
        db.getCollection("commodity_sales").insertOne(new Document("_id", empty)
                .append("amountInvoicedTtc", new Decimal128(new BigDecimal("1000")))
                .append("settlement", settlement(f("totalValue", "999"))));

        new M099_RecomputeCommoditySettlementTotals().execute(db);

        Document a = db.getCollection("commodity_sales").find(new Document("_id", withParts)).first()
                .get("settlement", Document.class);
        assertEquals(new BigDecimal("18980000"), a.get("totalValue", Decimal128.class).bigDecimalValue());
        assertEquals(new BigDecimal("50000"), a.get("totalDeductions", Decimal128.class).bigDecimalValue());
        assertEquals(new BigDecimal("18930000"), a.get("netAmount", Decimal128.class).bigDecimalValue());

        Document b = db.getCollection("commodity_sales").find(new Document("_id", empty)).first()
                .get("settlement", Document.class);
        assertNull(b.get("totalValue"));
        assertNull(b.get("netAmount"));
    }
}
