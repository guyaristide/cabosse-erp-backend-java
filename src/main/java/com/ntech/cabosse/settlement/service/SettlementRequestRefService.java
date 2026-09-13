package com.ntech.cabosse.settlement.service;

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

/** Références des demandes de règlement {@code DR-YYYY-NNNN}. */
@ApplicationScoped
public class SettlementRequestRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "settlement_request:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String next() {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", KEY_PREFIX + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("DR-%d-%04d", year, seq);
    }
}
