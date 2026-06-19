package com.ntech.cabosse.campaign.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

/**
 * Génère les références séquentielles {@code CMP-YYYY-NN} pour les
 * campagnes. Identique au pattern {@code MemberRefService}, scoped par
 * (année agricole). Le compteur tient sur 2 digits — suffisant pour une
 * coopérative qui ne dépasse pas quelques campagnes par an.
 */
@ApplicationScoped
public class CampaignRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "campaign:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String next(int campaignYear) {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        String key = KEY_PREFIX + campaignYear;
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("CMP-%d-%02d", campaignYear, seq);
    }
}
