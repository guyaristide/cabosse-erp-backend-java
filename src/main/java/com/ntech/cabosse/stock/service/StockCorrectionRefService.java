package com.ntech.cabosse.stock.service;

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
 * Références {@code COR-YYYY-NNNN} des corrections de stock.
 *
 * <p>Quatre chiffres : un magasin brasse quelques fois par semaine, pas
 * quelques fois par heure.</p>
 */
@ApplicationScoped
public class StockCorrectionRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "stock_correction:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String next() {
        int year = Year.now().getValue();
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", KEY_PREFIX + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("COR-%d-%04d", year, seq);
    }
}
