package com.ntech.cabosse.production.service;

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
 * Génère les références séquentielles {@code OF-YYYY-NNNN} (Ordre de
 * Fabrication) ET {@code LOT-YYYY-NNNN} (étiquette de lot). Deux
 * compteurs séparés dans la collection {@code counters} : clés
 * {@code manufacturing_order:YYYY} et {@code lot:YYYY}.
 *
 * <p>Le compteur {@code lot} est exposé en {@code public} pour les
 * usages futurs (autres modules qui généreraient un lot, comme une
 * réception qui voudrait étiqueter sa marchandise). Pour l'instant
 * seul l'OF s'en sert au moment du {@code create}.</p>
 */
@ApplicationScoped
public class ManufacturingOrderRefService {

    private static final String COLLECTION = "counters";
    private static final String OF_KEY_PREFIX = "manufacturing_order:";
    private static final String LOT_KEY_PREFIX = "lot:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<Document> coll() {
        return tenantDb.database().getCollection(COLLECTION);
    }

    /** Génère la prochaine référence d'OF {@code OF-YYYY-NNNN}. */
    public String nextOfRef() {
        int year = Year.now().getValue();
        return String.format("OF-%d-%04d", year, increment(OF_KEY_PREFIX + year));
    }

    /** Génère la prochaine étiquette de lot {@code LOT-YYYY-NNNN}. */
    public String nextLotRef() {
        int year = Year.now().getValue();
        return String.format("LOT-%d-%04d", year, increment(LOT_KEY_PREFIX + year));
    }

    private long increment(String key) {
        Document updated = coll().findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        return updated != null ? updated.getLong("seq") : 1L;
    }
}
