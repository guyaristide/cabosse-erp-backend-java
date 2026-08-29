package com.ntech.cabosse.commodity.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Year;

/**
 * Références séquentielles du négoce cacao (NEG-02) : contrats {@code CTR-YYYY-NNNN}
 * et ventes {@code VC-YYYY-NNNN}.
 */
@ApplicationScoped
public class CommodityRefService {

    private static final String COLLECTION = "counters";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String nextContract() { return next("sales_contract", "CTR"); }

    public String nextSale() { return next("commodity_sale", "VC"); }

    private String next(String keyPrefix, String refPrefix) {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", keyPrefix + ":" + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("%s-%d-%04d", refPrefix, year, seq);
    }
}
