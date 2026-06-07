package com.ntech.cabosse.accounting.service;

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
 * Génère les références séquentielles {@code EC-YYYY-NNNNNN} (6 chiffres,
 * jusqu'à 999 999 pièces par an et par tenant). Stockées dans la
 * collection {@code counters} partagée avec les autres compteurs métier
 * du tenant ({@code BC-…}, {@code FA-…}…), clé {@code accounting:YYYY}.
 */
@ApplicationScoped
public class JournalPieceRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "accounting:";

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
        return String.format("EC-%d-%06d", year, seq);
    }
}
