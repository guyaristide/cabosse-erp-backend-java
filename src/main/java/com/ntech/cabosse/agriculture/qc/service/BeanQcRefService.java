package com.ntech.cabosse.agriculture.qc.service;

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

/** Génère les références {@code QC-YYYY-NNNN} et {@code LOT-FEVE-YYYY-NNNN}. */
@ApplicationScoped
public class BeanQcRefService {

    private static final String COLLECTION = "counters";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String nextQc() {
        return next("qc:", "QC");
    }

    public String nextBeanLot() {
        return next("lot-feve:", "LOT-FEVE");
    }

    private String next(String keyPrefix, String labelPrefix) {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        String key = keyPrefix + year;
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("%s-%d-%04d", labelPrefix, year, seq);
    }
}
