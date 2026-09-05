package com.ntech.cabosse.collector.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Year;

/**
 * Références séquentielles {@code DAP-DEL-YYYY-NNNN} pour les demandes
 * d'approbation de paiement du reliquat aux délégués. « DAP » est le nom
 * de l'expert (document V2 du 04/09/2026) ; l'année sépare les compteurs
 * de deux campagnes. Le préfixe {@code REM-DEL} de la première heure n'a
 * jamais été déployé, le compteur continue.
 */
@ApplicationScoped
public class AdvanceRefundRefService {

    public static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "advance_refund:";

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
        return String.format("DAP-DEL-%d-%04d", year, seq);
    }
}
