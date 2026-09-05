package com.ntech.cabosse.collector.service;

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

/** Références séquentielles {@code DA-DEL-YYYY-NNNN} pour les demandes
 * d'avance aux délégués.
 *
 * <p>Forme demandée par l'expert le 03/09/2026, {@code DA-DEL} pour une
 * demande d'avance à un délégué et {@code DA-PRO} à un producteur. Son
 * exemple omet l'année ; elle est conservée parce qu'une référence
 * s'imprime et se cite, et que deux campagnes croiseraient leurs
 * compteurs sans elle.</p>
 *
 * <p>Les références déjà émises gardent leur forme {@code AV-YYYY-NNNN} :
 * une référence imprimée ne se réécrit pas, et les deux formes
 * cohabiteront le temps que les anciennes sortent de la circulation. */
@ApplicationScoped
public class CollectorAdvanceRefService {

    public static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "collector_advance:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<Document> coll() {
        return tenantDb.database().getCollection(COLLECTION);
    }

    public String next() {
        int year = Year.now().getValue();
        Document updated = coll().findOneAndUpdate(
                Filters.eq("_id", KEY_PREFIX + year),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("DA-DEL-%d-%04d", year, seq);
    }
}
