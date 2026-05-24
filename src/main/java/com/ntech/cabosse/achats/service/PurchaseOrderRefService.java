package com.ntech.cabosse.achats.service;

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
 * Génère des références séquentielles {@code BC-YYYY-NNNN} pour les bons
 * de commande. La séquence est tenant-locale (collection {@code counters}
 * dans la base du tenant) et par année — réinitialisée au 1er janvier.
 *
 * <p>L'incrément est atomique via {@code findOneAndUpdate(upsert)} —
 * deux uploads concurrents ne peuvent pas tomber sur le même numéro.</p>
 */
@ApplicationScoped
public class PurchaseOrderRefService {

    public static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "purchase_order:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<Document> coll() {
        return tenantDb.database().getCollection(COLLECTION);
    }

    /** Génère la prochaine référence pour l'année courante. */
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
        return String.format("BC-%d-%04d", year, seq);
    }
}
