package com.ntech.cabosse.producerpurchase.service;

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
 * Références des bordereaux de livraison {@code BL-YYYY-NNNN}.
 *
 * <p>Un bordereau est ce qu'un délégué apporte en une fois : les reçus qu'il
 * remet ensemble. Ce n'est pas un objet en base, seulement une clé de
 * regroupement portée par chaque reçu, pour que la matière garde une seule
 * origine et que la traçabilité producteur ne se perde pas en route.</p>
 */
@ApplicationScoped
public class DeliveryNoteRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "delivery_note:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    public String next() {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        Document updated = coll.findOneAndUpdate(
                Filters.eq("_id", KEY_PREFIX + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("BL-%d-%04d", year, seq);
    }
}
