package com.ntech.cabosse.members.service;

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

/** Génère les références séquentielles {@code MB-YYYY-NNNN} pour les membres. */
@ApplicationScoped
public class MemberRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "member:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String next() {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        String key = KEY_PREFIX + year;
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("MB-%d-%04d", year, seq);
    }
}
