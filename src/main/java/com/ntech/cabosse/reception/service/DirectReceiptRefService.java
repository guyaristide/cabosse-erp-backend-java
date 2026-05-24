package com.ntech.cabosse.reception.service;

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
 * Génère les références séquentielles {@code RD-YYYY-NNNN} (Réception
 * Directe) tenant-locales, séparées du compteur BC. Compteur dans la
 * même collection {@code counters} (clé {@code direct_receipt:YYYY}).
 */
@ApplicationScoped
public class DirectReceiptRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "direct_receipt:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<Document> coll() {
        return tenantDb.database().getCollection(COLLECTION);
    }

    public String next() {
        int year = Year.now().getValue();
        String key = KEY_PREFIX + year;
        Document updated = coll().findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("RD-%d-%04d", year, seq);
    }
}
