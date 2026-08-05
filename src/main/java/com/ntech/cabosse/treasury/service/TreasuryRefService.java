package com.ntech.cabosse.treasury.service;

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

/** Références des transferts {@code TRF-YYYY-NNNN} et points de caisse {@code PDC-YYYY-NNNN}. */
@ApplicationScoped
public class TreasuryRefService {

    private static final String COLLECTION = "counters";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String nextTransfer() { return next("treasury_transfer:", "TRF"); }

    public String nextCashCount() { return next("cash_count:", "PDC"); }

    private String next(String keyPrefix, String prefix) {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", keyPrefix + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("%s-%d-%04d", prefix, year, seq);
    }
}
